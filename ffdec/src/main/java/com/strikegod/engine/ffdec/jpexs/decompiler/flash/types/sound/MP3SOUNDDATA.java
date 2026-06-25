/*
 *  Copyright (C) 2010-2026 JPEXS, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 */
package com.strikegod.engine.ffdec.jpexs.decompiler.flash.types.sound;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWFInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MP3 sound data wrapper kept for raw SWF metadata/import code.
 *
 * @author JPEXS
 */
public class MP3SOUNDDATA {

    public int seekSamples;

    public List<MP3FRAME> frames;

    public MP3SOUNDDATA(SWFInputStream sis, boolean raw) throws IOException {
        if (!raw) {
            seekSamples = sis.readSI16("seekSamples");
        }
        frames = new ArrayList<>();
        byte[] data = sis.readBytesEx(sis.available(), "soundStream");
        if (data.length > 0) {
            frames.add(MP3FRAME.fromBytes(data));
        }
    }

    public int sampleCount() {
        int r = 0;
        for (MP3FRAME f : frames) {
            r += f.getSampleCount();
        }
        return r;
    }
}
