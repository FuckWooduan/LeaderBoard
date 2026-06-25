/*
 *  Copyright (C) 2010-2026 JPEXS, All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWF;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWFInputStream;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWFOutputStream;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.types.annotations.Multiline;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.types.annotations.SWFVersion;
import com.strikegod.engine.ffdec.jpexs.helpers.ByteArrayRange;
import java.io.IOException;

/**
 * Metadata tag - metadata of the SWF file.
 *
 * @author JPEXS
 */
@SWFVersion(from = 1)
public class MetadataTag extends Tag {

    public static final int ID = 77;

    public static final String NAME = "Metadata";

    @Multiline
    public String xmlMetadata;

    /**
     * Constructor
     *
     * @param swf SWF
     */
    public MetadataTag(SWF swf) {
        super(swf, ID, NAME, null);
        xmlMetadata = "";
    }

    public MetadataTag(SWFInputStream sis, ByteArrayRange data) {
        super(sis.getSwf(), ID, NAME, data);
        readData(sis, data, 0, false, false, false);
    }

    @Override
    public final void readData(
            SWFInputStream sis,
            ByteArrayRange data,
            int level,
            boolean parallel,
            boolean skipUnusualTags,
            boolean lazy) {
        try {
            xmlMetadata = sis.readString("xmlMetadata");
        } catch (IOException ex) {
            // ignored
        }
    }

    /**
     * Gets data bytes
     *
     * @param sos SWF output stream
     * @throws IOException On I/O error
     */
    @Override
    public void getData(SWFOutputStream sos) throws IOException {
        sos.writeString(xmlMetadata);
    }
}
