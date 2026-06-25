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
package com.strikegod.engine.ffdec.jpexs.decompiler.flash.exporters;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.AbortRetryIgnoreHandler;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.EventListener;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.ReadOnlyTagList;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.RetryTask;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWFInputStream;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.configuration.Configuration;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.exporters.modes.FontExportMode;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.exporters.settings.FontExportSettings;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.Tag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.base.FontTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.DottedChain;
import com.strikegod.engine.ffdec.jpexs.helpers.CancellableWorker;
import com.strikegod.engine.ffdec.jpexs.helpers.Helper;
import com.strikegod.engine.ffdec.jpexs.helpers.Path;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Font exporter.
 *
 * @author JPEXS
 */
public class FontExporter {

    public List<File> exportFonts(
            AbortRetryIgnoreHandler handler,
            String outdir,
            ReadOnlyTagList tags,
            final FontExportSettings settings,
            EventListener evl)
            throws IOException, InterruptedException {
        List<File> ret = new ArrayList<>();
        if (CancellableWorker.isInterrupted()) {
            return ret;
        }

        if (tags.isEmpty()) {
            return ret;
        }

        File foutdir = new File(outdir);
        Path.createDirectorySafe(foutdir);

        int count = 0;
        for (Tag t : tags) {
            if (t instanceof FontTag) {
                count++;
            }
        }

        if (count == 0) {
            return ret;
        }

        int currentIndex = 1;
        for (Tag t : tags) {
            if (t instanceof FontTag) {
                if (evl != null) {
                    evl.handleExportingEvent("font", currentIndex, count, t.getName());
                }

                final FontTag st = (FontTag) t;
                String ext = ".ttf";
                if (settings.mode == FontExportMode.WOFF) {
                    ext = ".woff";
                }
                final File file =
                        new File(outdir + File.separator + Helper.makeFileName(st.getCharacterExportFileName() + ext));
                new RetryTask(
                                () -> {
                                    exportFont(st, settings.mode, file);
                                },
                                handler)
                        .run();

                Set<String> classNames = st.getClassNames();
                if (Configuration.as3ExportNamesUseClassNamesOnly.get() && !classNames.isEmpty()) {
                    for (String className : classNames) {
                        if (Configuration.autoDeobfuscateIdentifiers.get()) {
                            className = DottedChain.parseNoSuffix(className)
                                    .toPrintableString(new LinkedHashSet<>(), st.getSwf(), true);
                        }
                        File classFile = new File(outdir + File.separator + Helper.makeFileName(className + ext));
                        new RetryTask(
                                        () -> {
                                            Files.copy(
                                                    file.toPath(),
                                                    classFile.toPath(),
                                                    StandardCopyOption.REPLACE_EXISTING);
                                        },
                                        handler)
                                .run();
                        ret.add(classFile);
                    }
                    file.delete();
                } else {
                    ret.add(file);
                }

                if (CancellableWorker.isInterrupted()) {
                    break;
                }

                if (evl != null) {
                    evl.handleExportedEvent("font", currentIndex, count, t.getName());
                }

                currentIndex++;
            }
        }

        return ret;
    }

    public byte[] exportFont(final FontTag t, FontExportMode mode) {
        try {
            String ext = null;
            switch (mode) {
                case TTF:
                    ext = ".ttf";
                    break;
                case WOFF:
                    ext = ".woff";
            }
            File f = File.createTempFile("temp", ext);
            exportFont(t, mode, f);
            return Helper.readFile(f.getPath());
        } catch (IOException ex) {
            Logger.getLogger(FontExporter.class.getName()).log(Level.SEVERE, null, ex);
        }
        return SWFInputStream.BYTE_ARRAY_EMPTY;
    }

    public void exportFont(FontTag ft, FontExportMode mode, File file) throws IOException {
        throw new IOException("TTF/WOFF font export is not included in the StrikeGod Engine FFDEC build");
    }
}
