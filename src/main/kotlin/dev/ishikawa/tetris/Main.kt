@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.ishikawa.tetris

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.lang.RuntimeException
import java.util.stream.Collectors

fun main(args: Array<String>) {
    val game = Game.init()

    runBlocking {
        game.forwardingClock()
        game.waitForUserCommand()
        
        while(true) {
            val command = game.getCommand()
            log(LogLevel.DEBUG) { println("next main loop... command = $command") }

            when(command) {
                Command.TICK -> {
                    when (game.tick()) {
                        is TickResult.Failure -> break
                        is TickResult.Success -> { /*noop*/ }
                    }
                }
                else -> game.moveTetromino(command = command)
            }

            game.refreshView()
        }

        game.over()
    }

    println("score is: ${game.score}")
}

class Game(
    private val field: Array<Array<Block?>>,
    var score: Int,
    private val commandQueue: Channel<Command>,
    private val tetrominoQueue: ArrayDeque<TetrominoType>,
    private var currentTetromino: Tetromino
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun waitForUserCommand() {
        scope.launch {
            while (true) {
                val input = readln()
                if (input.isBlank()) continue

                val commandChr = input.first()
                val command = when (commandChr) {
                    'a' -> Command.LEFT
                    's' -> Command.DOWN
                    'd' -> Command.RIGHT
                    'w' -> Command.ROTATE
                    else -> Command.IGNORED
                }
                log(LogLevel.DEBUG) { println("input command = $command") }

                if (command != Command.IGNORED) {
                    commandQueue.send(command)
                }
            }
        }
    }

    fun nextTetromino(): Tetromino {
        val nextTetrominoType = tetrominoQueue.removeFirst()
        supplyTetrominoQueue(tetrominoQueue = tetrominoQueue)

        return Tetromino(
            type = nextTetrominoType,
            col = 4,
            row = 1,
            direction = TetrominoDirection.UP,
            color = "G"
        )
    }

    suspend fun getCommand(): Command {
        return commandQueue.receive()
    }

    // 一定間隔でtickを入れる. ただしcommandQueueに要素があれば、またはuser input直後のdelay中であればskip
    suspend fun forwardingClock() {
        scope.launch {
            while (true) {
                if (commandQueue.isEmpty) {
                    log(LogLevel.DEBUG) { println("ticktimer: command is empty. putting TICK...") }
                    commandQueue.send(Command.TICK)
                } else {
                    log(LogLevel.DEBUG) { println("ticktimer: command is not empty. do nothing... commandQueue: ${commandQueue}") }
                }

                delay(2000)
            }
        }
    }

    // if action == TICK then move mino or fail
    fun tick(): TickResult {
        log(LogLevel.DEBUG) { println("ticking...") }

        return if (currentTetromino.canMoveTo(direction = Direction.DOWN, field = field)) {
            log(LogLevel.DEBUG) { println("allowed to move...") }
            currentTetromino.moveTo(direction = Direction.DOWN)
            TickResult.Success("moved")
        } else {
            log(LogLevel.DEBUG) { println("cannot move. trying to fix...") }
            tryFixing()
        }
    }

    /*
* assuming that currentTetromino is touching some block
* */
    private fun tryFixing(): TickResult {
        log(LogLevel.DEBUG) { println("checkIfGameOver...") }
        if (checkIfGameOver()) {
            return TickResult.Failure("game over!")
        }
        log(LogLevel.DEBUG) { println("not GameOver...") }

        fixCurrentTetromino()
        score += removeLines()
        currentTetromino = nextTetromino()

        return TickResult.Success("fixed tetromino and set new one")
    }

    private fun fixCurrentTetromino() {
        currentTetromino.blockPositions().forEach { position ->
            assert(field[position.second][position.first] == null)
            field[position.second][position.first] = Block(color = currentTetromino.color)
        }
    }

    private fun checkIfGameOver(): Boolean {
        return currentTetromino.hasConflict(
            positions = currentTetromino.blockPositions(),
            field = field
        )
    }

    /**
     * returns how many lines are removed
     * */
    private fun removeLines(): Int {
        var score = 0
        // TODO: currentTetrominoが存在するrowだけみればよい
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

    //if command != TICK then move mino
    fun moveTetromino(command: Command) {
        log(LogLevel.DEBUG) { println("moving.... $command") }

        when (command) {
            Command.ROTATE -> {
                if (currentTetromino.canRotate(field = field)) {
                    currentTetromino.rotate()
                } else {
                    // do nothing
                }
            }

            Command.LEFT, Command.RIGHT, Command.DOWN -> {
                val direction = Direction.values().firstOrNull { it.name == command.name } ?: throw RuntimeException("enum doesn't have its corresponding direction")
                if (currentTetromino.canMoveTo(field = field, direction = direction)) {
                    currentTetromino.moveTo(direction = direction)
                } else {
                    // do nothing
                }
            }

            else -> { /* noop */ }
        }
    }

    fun refreshView() {
        val blockPositions: Map<Int, Set<Int>> = currentTetromino.blockPositionsMap()

        val fieldInView = field
            .mapIndexed { rowIdx, row ->
                "<!" + row.mapIndexed { colIdx, cell ->
                    if (blockPositions.getOrDefault(rowIdx, emptySet()).contains(colIdx)) {
                        currentTetromino.color
                    } else {
                        cell?.color ?: " "
                    }
                }.joinToString("") + "!>"
            }
            .joinToString("\n") + "\n  ==========  "
        println(fieldInView)
    }

    fun over() {
        println("game over!")
        scope.cancel("game over")
    }

    companion object {
        fun init(): Game {
            val tetrominoQueue = initTetrominoQueue()
            val currentTetromino = Tetromino(
                type = tetrominoQueue.removeFirst(),
                col = 4,
                row = 1, // TODO: center blockの初期位置はtypeによる
                direction = TetrominoDirection.UP,
                color = "G"
            )

            return Game(
                field = initField(),
                score = 0,
                commandQueue = Channel(capacity = Channel.UNLIMITED),
                tetrominoQueue = initTetrominoQueue(),
                currentTetromino = currentTetromino
            )
        }

        fun initField(): Array<Array<Block?>> {
            return Array(HEIGHT) { initRow() }
        }
        fun initRow(): Array<Block?> {
            return Array(WIDTH) { null }
        }

        fun initTetrominoQueue(): ArrayDeque<TetrominoType> {
            val queue = ArrayDeque<TetrominoType>()
            supplyTetrominoQueue(tetrominoQueue = queue)
            supplyTetrominoQueue(tetrominoQueue = queue)
            supplyTetrominoQueue(tetrominoQueue = queue)
            return queue
        }

        fun supplyTetrominoQueue(tetrominoQueue: ArrayDeque<TetrominoType>) {
            tetrominoQueue.add(TetrominoType.values().random())
        }

        const val HEIGHT = 20
        const val WIDTH = 10
    }
}


// a cell of the field
data class Block(
   val color: String // emoji
)

data class Tetromino(
    private val type: TetrominoType,
    private var direction: TetrominoDirection,
    // position(col, row) of the center block of this Tetromino.
    private var col: Int,
    private var row: Int,
    val color: String
) {
    /**
     * returns absolute positions of each block of this Tetromino.
     * */
    fun blockPositions(directionToApply: TetrominoDirection = direction): Array<Pair<Int, Int>> {
        val relativePositions = when(directionToApply) {
            TetrominoDirection.RIGHT -> type.rightDirectionRelativePositions
            TetrominoDirection.LEFT -> type.leftDirectionRelativePositions
            TetrominoDirection.DOWN -> type.downDirectionRelativePositions
            TetrominoDirection.UP -> type.upDirectionRelativePositions
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

        if(result) {
            log(LogLevel.DEBUG) { println("has conflict!!") }
        }

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

    fun nextDirection(): TetrominoDirection {
        return when(direction) {
            TetrominoDirection.UP -> TetrominoDirection.RIGHT
            TetrominoDirection.RIGHT  -> TetrominoDirection.DOWN
            TetrominoDirection.DOWN -> TetrominoDirection.LEFT
            TetrominoDirection.LEFT -> TetrominoDirection.UP
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

enum class TetrominoDirection {
    UP, RIGHT, DOWN, LEFT
}

sealed class TickResult {
    data class Success(val value: String): TickResult()
    data class Failure(val value: String): TickResult()
}


enum class TetrominoType(
    val upDirectionRelativePositions: Array<Pair<Int, Int>>,
    val rightDirectionRelativePositions: Array<Pair<Int, Int>>,
    val downDirectionRelativePositions: Array<Pair<Int, Int>>,
    val leftDirectionRelativePositions: Array<Pair<Int, Int>>,
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
    );

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