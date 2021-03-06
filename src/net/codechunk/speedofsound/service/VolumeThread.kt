package net.codechunk.speedofsound.service

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * Smooth volume thread. Does its best to approach a set volume rather than
 * jumping to it directly. Has a few tunable constants to tweak for better
 * performance.
 */
class VolumeThread
/**
 * Start up the thread and set the thread name.
 *
 * @param context Application context
 */
(context: Context) : Thread() {

    // FIXME: this is a java hold-over that I didn't fix when converting to kotlin
    private val lock = java.lang.Object()
    private val signal = java.lang.Object()
    private val audioManager: AudioManager
    private val maxVolume: Int

    /**
     * The volume we want to approach. Between 0 and 1.
     */
    private var targetVolume: Float = 0.toFloat()

    init {
        this.audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        this.maxVolume = this.audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        this.name = TAG

        Log.d(TAG, "System max volume is " + this.maxVolume)
    }

    /**
     * Set a new target volume.
     *
     * @param volume New target volume from 0 to 1
     */
    fun setTargetVolume(volume: Float) {
        // only set & wake if the target has actually changed
        if (volume != this.targetVolume) {
            Log.v(TAG, "Setting target volume to " + volume)
            synchronized(this.lock) {
                this.targetVolume = volume
            }

            // wake the thread up
            synchronized(this.signal) {
                this.signal.notifyAll()
            }
        }
    }

    /**
     * Thread runner. Smoothly adjusts the volume until interrupted.
     */
    override fun run() {
        Log.d(TAG, "Thread starting")

        // get the current system volume
        val systemVolume = this.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        var currentVolume = systemVolume / this.maxVolume.toFloat()
        this.setTargetVolume(currentVolume)
        Log.d(TAG, "Current volume is $currentVolume")

        run loop@{
            while (!this.isInterrupted) {
                // sleep for a while
                try {
                    Thread.sleep(VolumeThread.UPDATE_DELAY.toLong())
                } catch (e: InterruptedException) {
                    break
                }

                // safely grab the target volume
                var targetVolume = 0.0f
                synchronized(this.lock) {
                    targetVolume = this.targetVolume
                }

                // if the volume is matched, just sleep
                if (currentVolume == targetVolume) {
                    Log.v(TAG, "Thread sleeping")
                    synchronized(this.signal) {
                        try {
                            this.signal.wait()
                        } catch (e: InterruptedException) {
                            return@loop
                        }

                    }
                    Log.v(TAG, "Thread awoken")

                    // get the updated volume
                    synchronized(this.lock) {
                        targetVolume = this.targetVolume
                    }
                }

                // if the target is close enough, just use it
                val newVolume: Float
                if (Math.abs(currentVolume - targetVolume) < VolumeThread.VOLUME_THRESHOLD) {
                    newVolume = targetVolume
                } else {
                    // otherwise, gently approach the target
                    var approach = (targetVolume - currentVolume) * VolumeThread.APPROACH_RATE

                    // don't approach more quickly than the max
                    if (Math.abs(approach) > VolumeThread.MAX_APPROACH) {
                        // approach with the max rate, but with the same sign as the
                        // original
                        if (approach < 0) {
                            approach = -VolumeThread.MAX_APPROACH
                        } else {
                            approach = VolumeThread.MAX_APPROACH
                        }
                    }

                    newVolume = currentVolume + approach
                }

                // set the volume
                try {
                    val newSystemVolume = (this.maxVolume * newVolume).toInt()
                    this.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newSystemVolume, 0)
                    currentVolume = newVolume

                    Log.v(TAG, "New volume is $newVolume translated to $newSystemVolume")
                } catch (e: SecurityException) {
                    // fucking Samsung devices throw this when that stupid "you might
                    // hurt your ears" pop-up comes up when changing the volume.
                    Log.e(TAG, "SecurityException when trying to change the volume", e)
                }
            }
        }

        Log.d(TAG, "Thread exiting")
    }

    companion object {
        private const val TAG = "VolumeThread"

        /**
         * Rate in milliseconds at which to update the volume.
         */
        private const val UPDATE_DELAY = 150

        /**
         * Threshold where we're "close enough" to the target volume to jump
         * straight to it.
         */
        private const val VOLUME_THRESHOLD = 0.03f

        /**
         * Approach rate. Heavily related to the update delay.
         */
        private const val APPROACH_RATE = 0.3f

        /**
         * Maximum speed to approach the target volume. This shouldn't be too high
         * or a jump in volume may be noticed.
         */
        private const val MAX_APPROACH = 0.06f
    }

}
