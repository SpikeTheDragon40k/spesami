package it.spesami.core.sync

import java.util.Locale

/**
 * Hybrid logical clock stamps: wall-clock milliseconds, a counter and the device id, encoded as a fixed-width
 * string (`000001762171200000.000000.<deviceId>`) so that comparing two stamps is comparing two strings.
 *
 * Every device moves its clock past the newest stamp it has seen, so an edit made after seeing another one
 * always gets a higher stamp, whatever the phones' clocks say. A phone whose clock runs a year ahead can win a
 * conflict with an edit it never saw, but every edit made anywhere after a sync is stamped later than all of its
 * stamps: it cannot win forever. The device id makes two stamps from different devices never equal.
 */
object Hlc {
    private val pattern = Regex("""\d{15}\.\d{6}\.[A-Za-z0-9_-]{1,64}""")

    fun encode(wall: Long, counter: Int, device: String): String =
        String.format(Locale.ROOT, "%015d.%06d.%s", wall.coerceIn(0, 999_999_999_999_999), counter, device)

    fun isValid(stamp: String): Boolean = pattern.matches(stamp)

    fun wall(stamp: String): Long = stamp.substring(0, 15).toLong()

    private fun counter(stamp: String): Int = stamp.substring(16, 22).toInt()

    /** The lowest stamp of [stamp]'s device: for values nobody chose (seeded defaults), so any real edit wins. */
    fun base(stamp: String): String = encode(0, 0, stamp.substringAfterLast('.'))

    /** Issues stamps for one device. [now] is the device's clock in epoch milliseconds. */
    class Clock(private val device: String, private val now: () -> Long) {
        private var wall = 0L
        private var counter = 0

        init {
            require(Regex("[A-Za-z0-9_-]{1,64}").matches(device)) { "invalid device id" }
        }

        /** Moves the clock past [stamp] (from this or another device). */
        fun observe(stamp: String?): Clock {
            if (stamp == null || !isValid(stamp)) return this
            val w = wall(stamp)
            val c = counter(stamp)
            if (w > wall || (w == wall && c > counter)) {
                wall = w
                counter = c
            }
            return this
        }

        /** A stamp higher than every stamp issued or observed so far. */
        fun next(): String {
            val n = now()
            if (n > wall) {
                wall = n
                counter = 0
            } else {
                counter++
                if (counter > 999_999) {
                    wall++
                    counter = 0
                }
            }
            return encode(wall, counter, device)
        }
    }
}
