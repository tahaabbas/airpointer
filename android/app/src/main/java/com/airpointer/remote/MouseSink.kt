package com.airpointer.remote

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Something that can receive mouse/keyboard input: the desktop app (Wi-Fi/Bluetooth) or a Bluetooth HID host. */
interface MouseSink {
    val label: String
    fun move(dx: Int, dy: Int)
    fun button(button: Int, action: Int)
    fun scroll(vertical: Int, horizontal: Int)
    fun zoom(steps: Int)
    fun key(key: Int)
    fun text(s: String)
    fun action(action: Int)
    /** A normalized key combo from [KeyCombo.parse], e.g. "Alt+P". */
    fun combo(combo: String)
    /** Press and hold a single combo (auto-repeats like a held key) until [comboUp]. */
    fun comboDown(combo: String)
    fun comboUp()
    fun close()
}

/** Operations queued from the UI thread and executed on a background writer thread. */
sealed class Op {
    class Move(var dx: Int, var dy: Int) : Op()
    class Button(val button: Int, val action: Int) : Op()
    class Scroll(var v: Int, var h: Int) : Op()
    class Zoom(val steps: Int) : Op()
    class Key(val key: Int) : Op()
    class Text(val text: String) : Op()
    class Action(val action: Int) : Op()
    class Combo(val combo: String) : Op()
    class ComboDown(val combo: String) : Op()
    object ComboUp : Op()
    object Stop : Op()
}

/**
 * Base class: queues operations and runs them in order on a dedicated thread. Consecutive moves
 * (and scrolls) are merged when the link is slower than the sensor rate, which keeps latency low.
 */
abstract class QueuedSink(name: String) : MouseSink {
    private val queue = LinkedBlockingQueue<Op>()
    @Volatile protected var closed = false
    private val thread = Thread({ loop() }, name)

    /**
     * How often [onKeepAlive] runs (0 = never). It runs on a fixed schedule even while input is
     * streaming, so the peer's replies keep arriving and the link is never mistaken for dead.
     */
    protected open val keepAliveMs: Long = 0

    protected fun startWorker() = thread.start()

    protected abstract fun execute(op: Op)
    protected open fun onKeepAlive() {}
    protected open fun flush() {}
    protected abstract fun onWorkerFailed(e: Exception)

    private fun loop() {
        val batch = ArrayList<Op>()
        var lastKeepAlive = System.currentTimeMillis()
        try {
            // Runs until the Stop marker, so ops queued before close() (e.g. button releases) are still sent.
            while (true) {
                val first = if (keepAliveMs > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeepAlive >= keepAliveMs) {
                        onKeepAlive()
                        flush()
                        lastKeepAlive = now
                    }
                    queue.poll(maxOf(1L, keepAliveMs - (now - lastKeepAlive)), TimeUnit.MILLISECONDS)
                } else {
                    queue.take()
                }
                if (first == null) continue
                batch.add(first)
                queue.drainTo(batch)
                var pending: Op? = null
                for (op in batch) {
                    if (op === Op.Stop) {
                        pending?.let { execute(it) }
                        flush()
                        return
                    }
                    pending = when {
                        pending is Op.Move && op is Op.Move -> pending.apply { dx += op.dx; dy += op.dy }
                        pending is Op.Scroll && op is Op.Scroll -> pending.apply { v += op.v; h += op.h }
                        else -> { pending?.let { execute(it) }; op }
                    }
                }
                pending?.let { execute(it) }
                batch.clear()
                flush()
            }
        } catch (e: InterruptedException) {
            // closing
        } catch (e: Exception) {
            if (!closed) onWorkerFailed(e)
        }
    }

    protected fun enqueue(op: Op) {
        if (!closed) queue.offer(op)
    }

    protected fun stopWorker() {
        closed = true
        queue.offer(Op.Stop)
    }

    override fun move(dx: Int, dy: Int) { if (dx != 0 || dy != 0) enqueue(Op.Move(dx, dy)) }
    override fun button(button: Int, action: Int) = enqueue(Op.Button(button, action))
    override fun scroll(vertical: Int, horizontal: Int) { if (vertical != 0 || horizontal != 0) enqueue(Op.Scroll(vertical, horizontal)) }
    override fun zoom(steps: Int) { if (steps != 0) enqueue(Op.Zoom(steps)) }
    override fun key(key: Int) = enqueue(Op.Key(key))
    override fun text(s: String) { if (s.isNotEmpty()) enqueue(Op.Text(s)) }
    override fun action(action: Int) = enqueue(Op.Action(action))
    override fun combo(combo: String) { if (combo.isNotEmpty()) enqueue(Op.Combo(combo)) }
    override fun comboDown(combo: String) { if (combo.isNotEmpty()) enqueue(Op.ComboDown(combo)) }
    override fun comboUp() = enqueue(Op.ComboUp)
}
