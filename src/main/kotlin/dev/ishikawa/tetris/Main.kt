package dev.ishikawa.tetris

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.lang.RuntimeException
import java.util.stream.Collectors

fun main(args: Array<String>) {
    val game = Game.init(
        renderers = listOf(
//            StdoutRenderer(),
            StdoutEmojiRenderer(),
        ),
        userCommandRetriever = StdinUserCommandRetriever()
    )

    game.play()
    game.showResult()
}

class Game(
    private val field: Array<Array<Block?>>,
    var score: Int,
    private val commandQueue: Channel<Command>,
    private val tetraminoQueue: ArrayDeque<TetraminoType>,
    private var currentTetramino: Tetramino,

    private val userCommandRetriever: UserCommandRetriever,
    private val renderers: List<Renderer>
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun play() = runBlocking {
        startTickTimer()
        startUserInputTimer()

        refreshView()
        delay(TICK_DELAY_MSEC)

        while(true) {
            val command = getCommand()
            log(LogLevel.DEBUG) { println("new command = $command") }

            when(command) {
                Command.TICK -> {
                    when (tick()) {
                        is TickResult.Failure -> break
                        is TickResult.Success -> { /*noop*/ }
                    }
                }
                else -> moveTetramino(command = command)
            }

            refreshView()
        }

        clearnUp()
    }

    fun showResult() = println("score is: ${score}")

    private suspend fun startUserInputTimer() {
        scope.launch {
            while (true) {
                val command = userCommandRetriever.retrieve().also {
                    log(LogLevel.DEBUG) { println("input command = $it") }
                }

                if (command != Command.IGNORED) {
                    commandQueue.send(command)
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun startTickTimer() {
        scope.launch {
            while (true) {
                if (commandQueue.isEmpty) {
                    log(LogLevel.DEBUG) { println("ticktimer: command is empty. putting TICK...") }
                    commandQueue.send(Command.TICK)
                } else {
                    log(LogLevel.DEBUG) { println("ticktimer: command is not empty. do nothing... commandQueue: ${commandQueue}") }
                }

                delay(TICK_DELAY_MSEC)
            }
        }
    }

    private fun nextTetramino(): Tetramino {
        val nextTetraminoType = tetraminoQueue.removeFirst()
        supplyTetraminoQueue(tetraminoQueue = tetraminoQueue)

        return Tetramino(
            type = nextTetraminoType,
            direction = TetraminoDirection.UP
        )
    }

    private suspend fun getCommand(): Command {
        return commandQueue.receive()
    }

    private fun tick(): TickResult {
        log(LogLevel.DEBUG) { println("ticking...") }

        return if (currentTetramino.canMoveTo(direction = Direction.DOWN, field = field)) {
            log(LogLevel.DEBUG) { println("allowed to move...") }
            currentTetramino.moveTo(direction = Direction.DOWN)
            TickResult.Success("moved")
        } else {
            log(LogLevel.DEBUG) { println("cannot move. trying to fix...") }
            tryFixing()
        }
    }

    /**
     * assuming that currentTetramino is touching some block
     * */
    private fun tryFixing(): TickResult {
        if (checkIfGameOver()) {
            return TickResult.Failure("game over!")
        }

        fixCurrentTetramino()
        score += removeLines()
        currentTetramino = nextTetramino()

        return TickResult.Success("fixed tetramino and set new one")
    }

    private fun fixCurrentTetramino() {
        currentTetramino.blockPositions().forEach { position ->
            assert(field[position.second][position.first] == null)
            field[position.second][position.first] = Block(color = currentTetramino.color)
        }
    }

    private fun checkIfGameOver(): Boolean {
        return currentTetramino.hasConflict(
            positions = currentTetramino.blockPositions(),
            field = field
        )
    }

    /**
     * returns how many lines are removed
     * */
    private fun removeLines(): Int {
        var score = 0
        // TODO: currentTetraminoが存在するrowだけみればよい
        field.forEachIndexed { index, row ->
            val isFilledLine = row.all { it != null }
            if(isFilledLine) {
                score += 1
                // move all the rows above current row
                for (i in index downTo 1) {
                    field[i] = field[i-1]
                }
                field[0] = initRow()
            }
        }

        return score
    }

    private fun moveTetramino(command: Command) {
        log(LogLevel.DEBUG) { println("moving.... $command") }

        when (command) {
            Command.ROTATE -> {
                if (currentTetramino.canRotate(field = field)) {
                    currentTetramino.rotate()
                } else {
                    // do nothing
                }
            }

            Command.LEFT, Command.RIGHT, Command.DOWN -> {
                val direction = Direction.values().firstOrNull { it.name == command.name } ?: throw RuntimeException("enum doesn't have its corresponding direction")
                if (currentTetramino.canMoveTo(field = field, direction = direction)) {
                    currentTetramino.moveTo(direction = direction)
                } else {
                    // do nothing
                }
            }

            else -> { /* noop */ }
        }
    }

    private fun refreshView() {
        renderers.forEach { it.render(field = field, currentTetramino = currentTetramino) }
    }

    private fun clearnUp() {
        scope.cancel("clearnUp")
    }

    companion object {
        fun init(renderers: List<Renderer>, userCommandRetriever: UserCommandRetriever): Game {
            val tetraminoQueue = initTetraminoQueue()
            val currentTetramino = Tetramino(
                type = tetraminoQueue.removeFirst(),
                direction = TetraminoDirection.UP
            )

            return Game(
                field = initField(),
                score = 0,
                commandQueue = Channel(capacity = Channel.UNLIMITED),
                tetraminoQueue = initTetraminoQueue(),
                currentTetramino = currentTetramino,
                renderers = renderers,
                userCommandRetriever = userCommandRetriever
            )
        }

        private fun initField(): Array<Array<Block?>> {
            return Array(HEIGHT) { initRow() }
        }
        private fun initRow(): Array<Block?> {
            return Array(WIDTH) { null }
        }

       private  fun initTetraminoQueue(): ArrayDeque<TetraminoType> {
            val queue = ArrayDeque<TetraminoType>()
            supplyTetraminoQueue(tetraminoQueue = queue)
            supplyTetraminoQueue(tetraminoQueue = queue)
            supplyTetraminoQueue(tetraminoQueue = queue)
            return queue
        }

       private fun supplyTetraminoQueue(tetraminoQueue: ArrayDeque<TetraminoType>) {
           tetraminoQueue.add(TetraminoType.values().random())
       }

       const val HEIGHT = 20
       const val WIDTH = 10
       const val TICK_DELAY_MSEC = 1500L
    }
}

// a cell of the field
data class Block(
   val color: Color
)

data class Tetramino(
    private val type: TetraminoType,
    private var direction: TetraminoDirection,
    // position(col, row) of the center block of this Tetramino.
    private var col: Int = type.initialPosition.first,
    private var row: Int = type.initialPosition.second
) {
    val color: Color
        get() = type.color

    /**
     * returns absolute positions of each block of this Tetramino.
     * */
    fun blockPositions(directionToApply: TetraminoDirection = direction): Array<Pair<Int, Int>> {
        val relativePositions = when(directionToApply) {
            TetraminoDirection.RIGHT -> type.rightDirectionRelativePositions
            TetraminoDirection.LEFT -> type.leftDirectionRelativePositions
            TetraminoDirection.DOWN -> type.downDirectionRelativePositions
            TetraminoDirection.UP -> type.upDirectionRelativePositions
        }

        return relativePositions
            .map { Pair(col + it.first, row + it.second) }
            .toTypedArray()
    }

    /**
    * returns a Map that consists of keys(row) to values(col)
    * */
    fun blockPositionsMap(): Map<Int, Set<Int>> {
        return blockPositions().toList()
            .stream()
            .collect(Collectors.toMap(
                { it.second },
                { mutableSetOf(it.first)},
                {a, b -> a.apply { this.addAll(b) } }
            ))
    }

   fun hasConflict(positions: Array<Pair<Int, Int>>, field: Array<Array<Block?>>): Boolean {
        val result = positions.any { position ->
            if(!(0 until field[0].size).contains(position.first)) {
                log(LogLevel.DEBUG) { println("col/x is out of the field. position = $position") }
                return@any true
            }
            if(!(0 until field.size).contains(position.second)) {
                log(LogLevel.DEBUG) { println("row/y is out of the field. position = $position") }
                return@any true
            }
            if(field[position.second][position.first] != null) {
                log(LogLevel.DEBUG) { println("field has a block at the position. position = $position, block = ${field[position.second][position.first]}") }
                return@any true
            }

            return@any false
        }

        log(LogLevel.DEBUG) { if(result) println("has conflict!!") }

        return result
    }

    fun canMoveTo(field: Array<Array<Block?>>, direction: Direction): Boolean {
        val nextPositions = blockPositions().map {
            when(direction) {
                Direction.RIGHT -> Pair(it.first+1, it.second)
                Direction.DOWN -> Pair(it.first, it.second+1)
                Direction.LEFT -> Pair(it.first-1, it.second)
            }
        }.toTypedArray()
        log(LogLevel.DEBUG) { println("direction = $direction, currentPositions = ${blockPositions().toList()}, nextPositions = ${nextPositions.toList()}") }
        return !hasConflict(positions = nextPositions, field = field)
    }

    fun moveTo(direction: Direction) {
        when(direction) {
            Direction.RIGHT -> col += 1
            Direction.LEFT -> col -= 1
            Direction.DOWN -> row += 1
        }
    }

    fun nextDirection(): TetraminoDirection {
        return when(direction) {
            TetraminoDirection.UP -> TetraminoDirection.RIGHT
            TetraminoDirection.RIGHT  -> TetraminoDirection.DOWN
            TetraminoDirection.DOWN -> TetraminoDirection.LEFT
            TetraminoDirection.LEFT -> TetraminoDirection.UP
        }
    }

    fun canRotate(field: Array<Array<Block?>>): Boolean {
        val nextPositions = blockPositions(directionToApply = nextDirection())
        return !hasConflict(positions = nextPositions, field = field)
    }

    fun rotate() {
        direction = nextDirection()
    }
}

enum class TetraminoDirection {
    UP, RIGHT, DOWN, LEFT
}

sealed class TickResult {
    data class Success(val value: String): TickResult()
    data class Failure(val value: String): TickResult()
}


enum class TetraminoType(
    val upDirectionRelativePositions: Array<Pair<Int, Int>>,
    val rightDirectionRelativePositions: Array<Pair<Int, Int>>,
    val downDirectionRelativePositions: Array<Pair<Int, Int>>,
    val leftDirectionRelativePositions: Array<Pair<Int, Int>>,
    val color: Color,
    val initialPosition: Pair<Int, Int>
) {
    I(
        /*
        * 0    0 c 2 3   3    3 2 c 0
        * c              2
        * 2              c
        * 3              0
        * */
        arrayOf(Pair(0, -1), Pair(0, 0), Pair(0, 1), Pair(0, 2)),
        arrayOf(Pair(-1, 0), Pair(0, 0), Pair(1, 0), Pair(2, 0)),
        arrayOf(Pair(0, 1), Pair(0, 0), Pair(0, -1), Pair(0, -2)),
        arrayOf(Pair(1, 0), Pair(0, 0), Pair(-1, 0), Pair(-2, 0)),
        Color.LIGHT_BLUE,
        Pair(4, 1)
    ),
    O(
        /*
        * 0 c
        * 3 2
        * */
        arrayOf(Pair(-1, 0), Pair(0, 0), Pair(0, 1), Pair(-1, 1)),
        arrayOf(Pair(-1, 0), Pair(0, 0), Pair(0, 1), Pair(-1, 1)),
        arrayOf(Pair(-1, 0), Pair(0, 0), Pair(0, 1), Pair(-1, 1)),
        arrayOf(Pair(-1, 0), Pair(0, 0), Pair(0, 1), Pair(-1, 1)),
        Color.YELLOW,
        Pair(5, 0)
    ),
    S(
        /*
        *    c 0     3
        *  3 2       2 c
        *              0
        * */
        arrayOf(Pair(1, 0), Pair(0, 0), Pair(0, 1), Pair(-1, 1)),
        arrayOf(Pair(0, 1), Pair(0, 0), Pair(-1, 0), Pair(-1, -1)),
        arrayOf(Pair(1, 0), Pair(0, 0), Pair(0, 1), Pair(-1, 1)),
        arrayOf(Pair(0, 1), Pair(0, 0), Pair(-1, 0), Pair(-1, -1)),
        Color.GREEN,
        Pair(4, 0)
    ),
    Z(
        /*
        *  0 c       0
        *    2 3   2 c
        *          3
        * */
        arrayOf(Pair(-1, 0), Pair(0, 0), Pair(0, 1), Pair(1, 1)),
        arrayOf(Pair(0, -1), Pair(0, 0), Pair(-1, 0), Pair(-1, 1)),
        arrayOf(Pair(-1, 0), Pair(0, 0), Pair(0, 1), Pair(1, 1)),
        arrayOf(Pair(0, -1), Pair(0, 0), Pair(-1, 0), Pair(-1, 1)),
        Color.RED,
        Pair(4, 0)
    ),
    J(
        /*
         *    0   3       2 3   0 c 2
         *    c   2 c 0   c         3
         *  3 2           0
         * */
        arrayOf(Pair(0, -1), Pair(0, 0), Pair(0, 1), Pair(-1, 1)),
        arrayOf(Pair(1, 0), Pair(0, 0), Pair(-1, 0), Pair(-1, -1)),
        arrayOf(Pair(0, 1), Pair(0, 0), Pair(0, -1), Pair(1, -1)),
        arrayOf(Pair(-1, 0), Pair(0, 0), Pair(1, 0), Pair(1, 1)),
        Color.BLUE,
        Pair(5, 1)
    ),
    L(
        /*
         *  0     2 c 0   3 2       3
         *  c     3         c   0 c 2
         *  2 3             0
         * */
        arrayOf(Pair(0, -1), Pair(0, 0), Pair(0, 1), Pair(1, 1)),
        arrayOf(Pair(1, 0), Pair(0, 0), Pair(-1, 0), Pair(-1, 1)),
        arrayOf(Pair(0, 1), Pair(0, 0), Pair(0, -1), Pair(-1, -1)),
        arrayOf(Pair(-1, 0), Pair(0, 0), Pair(1, 0), Pair(1, -1)),
        Color.ORANGE,
        Pair(4, 1)
    ),
    T(
        /*
         *    3     0               2
         *  0 c 2   c 3   2 c 0   3 c
         *          2       3       0
         * */
        arrayOf(Pair(-1, 0), Pair(0, 0), Pair(1, 0), Pair(0, -1)),
        arrayOf(Pair(0, -1), Pair(0, 0), Pair(0, 1), Pair(1, 0)),
        arrayOf(Pair(1, 0), Pair(0, 0), Pair(-1, 0), Pair(0, 1)),
        arrayOf(Pair(0, 1), Pair(0, 0), Pair(0, -1), Pair(-1, 0)),
        Color.PURPLE,
        Pair(4, 1)
    );
}

enum class Color {
    LIGHT_BLUE, YELLOW, GREEN, RED, BLUE, ORANGE, PURPLE, WHITE, BLACK
}

enum class Command {
    ROTATE,
    RIGHT,
    DOWN,
    LEFT,
    IGNORED,
    TICK
}

enum class Direction {
    RIGHT, DOWN, LEFT
}

interface Renderer {
    fun render(field: Array<Array<Block?>>, currentTetramino: Tetramino)
}

class StdoutRenderer : Renderer {
    override fun render(field: Array<Array<Block?>>, currentTetramino: Tetramino) {
        val blockPositions: Map<Int, Set<Int>> = currentTetramino.blockPositionsMap()

        val fieldInView = field
            .mapIndexed { rowIdx, row ->
                "<!" + row.mapIndexed { colIdx, cell ->
                    if (blockPositions.getOrDefault(rowIdx, emptySet()).contains(colIdx)) {
                        currentTetramino.color
                    } else {
                        cell?.color ?: " "
                    }
                }.joinToString("") + "!>"
            }
            .joinToString("\n") + "\n  ==========  "

        println(fieldInView)
    }

    private fun calcChar(color: Color): String {
        return when(color) {
            Color.LIGHT_BLUE -> "L"
            Color.YELLOW -> "Y"
            Color.GREEN -> "G"
            Color.RED -> "R"
            Color.BLUE -> "B"
            Color.ORANGE -> "O"
            Color.PURPLE -> "P"
            Color.WHITE -> " "
            Color.BLACK -> " "
        }
    }
}

class StdoutEmojiRenderer : Renderer {
    override fun render(field: Array<Array<Block?>>, currentTetramino: Tetramino) {
        val blockPositions: Map<Int, Set<Int>> = currentTetramino.blockPositionsMap()

        val fieldInView = field
            .mapIndexed { rowIdx, row ->
                row.mapIndexed { colIdx, cell ->
                    if (blockPositions.getOrDefault(rowIdx, emptySet()).contains(colIdx)) {
                        calcEmoji(currentTetramino.color)
                    } else {
                        calcEmoji(cell?.color ?: Color.BLACK)
                    }
                }.joinToString("")
            }
            .joinToString("\n") + "\n"

        println(fieldInView)
    }

    private fun calcEmoji(color: Color): String {
        // 🟥🟧🟨🟩🟦🟪🟫⬜ ⬛
        return when(color) {
            Color.LIGHT_BLUE -> "\uD83D\uDFEB" // LIGHT_BLUEのemojiないので茶色
            Color.YELLOW -> "\uD83D\uDFE8"
            Color.GREEN -> "\uD83D\uDFE9"
            Color.RED -> "\uD83D\uDFE5"
            Color.BLUE -> "\uD83D\uDFE6"
            Color.ORANGE -> "\uD83D\uDFE7"
            Color.PURPLE -> "\uD83D\uDFEA"
            Color.WHITE -> "⬜"
            Color.BLACK -> "⬛"
        }
    }
}


interface UserCommandRetriever {
    fun retrieve(): Command
}

class StdinUserCommandRetriever : UserCommandRetriever {
    override fun retrieve(): Command {
        val input = readln()
        if (input.isBlank()) return Command.IGNORED

        val commandChr = input.first()
        return when (commandChr) {
            'a' -> Command.LEFT
            's' -> Command.DOWN
            'd' -> Command.RIGHT
            'w' -> Command.ROTATE
            else -> Command.IGNORED
        }
    }
}

val loglevel = LogLevel.INFO
fun log(thisLogLevel: LogLevel, lambda: () -> Unit) {
    if(thisLogLevel.ordinal <= loglevel.ordinal) {
        print("[${thisLogLevel.name}] ")
        lambda.invoke()
    }
}

enum class LogLevel {
    INFO, DEBUG
}