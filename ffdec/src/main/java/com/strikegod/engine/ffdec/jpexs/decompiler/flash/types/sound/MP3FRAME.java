/*
 *  Copyright (C) 2010-2026 JPEXS, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 */
package com.strikegod.engine.ffdec.jpexs.decompiler.flash.types.sound;

/**
 * Minimal MP3 frame metadata retained for SWF tag parsing.
 *
 * @author JPEXS
 */
public class MP3FRAME {

    private byte[] fullData;

    private MP3FRAME() {}

    public static MP3FRAME fromBytes(byte[] data) {
        MP3FRAME ret = new MP3FRAME();
        ret.setFullData(data);
        return ret;
    }

    public void setFullData(byte[] fullData) {
        this.fullData = fullData;
    }

    public byte[] getBytes() {
        return fullData;
    }

    public int getSampleCount() {
        return 0;
    }

    public boolean isStereo() {
        return true;
    }

    public int getSamplingRate() {
        return 0;
    }

    public int getBitRate() {
        return 0;
    }
}
