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
import java.io.OutputStream;

/**
 * NellyMoser sound decoder.
 *
 * @author JPEXS
 */
public class NellyMoserDecoder extends SoundDecoder {

    public NellyMoserDecoder(SoundFormat soundFormat) {
        super(soundFormat);
    }

    @Override
    public void decode(SWFInputStream sis, OutputStream os) throws IOException {
        throw new IOException("NellyMoser decode is not included in the StrikeGod Engine FFDEC build");
    }
}
