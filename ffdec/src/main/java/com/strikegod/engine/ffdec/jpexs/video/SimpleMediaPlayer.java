package com.strikegod.engine.ffdec.jpexs.video;

/**
 * Engine build intentionally does not ship VLC/vlcj video playback support.
 * SWF parsing and static frame rendering used by StrikeGod do not require it.
 */
public class SimpleMediaPlayer {

    public static boolean isAvailable() {
        return false;
    }

    public long getLength() {
        return 0;
    }

    public void addFrameListener(FrameListener listener) {}

    public void removeFrameListener(FrameListener listener) {}

    public void play(String file) {}

    public void stop() {}

    public float getPosition() {
        return 0;
    }

    public void setPosition(float position) {}

    public synchronized boolean isPaused() {
        return true;
    }

    public synchronized void setPaused(boolean val) {}

    public void pause() {}

    public boolean isFinished() {
        return true;
    }
}
