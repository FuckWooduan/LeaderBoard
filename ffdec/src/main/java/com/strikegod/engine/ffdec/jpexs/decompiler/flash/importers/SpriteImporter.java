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
package com.strikegod.engine.ffdec.jpexs.decompiler.flash.importers;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWF;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.DefineSpriteTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.Tag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.base.CharacterIdTag;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.tags.base.CharacterTag;
import com.strikegod.engine.ffdec.jpexs.helpers.CancellableWorker;
import com.strikegod.engine.ffdec.jpexs.helpers.Helper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sprite importer.
 *
 * @author JPEXS
 */
public class SpriteImporter {

    private void removeCharacters(Set<Integer> usedCharacters, SWF swf) {
        for (int ch : usedCharacters) {
            CharacterTag ct = swf.getCharacter(ch);
            if (ct == null) {
                continue;
            }
            if (!ct.getClassNames().isEmpty()) {
                continue;
            }
            if (ct.getExportName() != null) {
                continue;
            }
            Set<Integer> dependentCharacters = swf.getDependentCharacters(ch);
            if (dependentCharacters.isEmpty()) {
                Set<Integer> needed = new LinkedHashSet<>();
                Set<String> neededClasses = new LinkedHashSet<>();
                ct.getNeededCharacters(needed, neededClasses, swf);
                // TODO: handle classes?
                List<CharacterIdTag> attachedTags = swf.getCharacterIdTags(ch);
                for (CharacterIdTag cit : attachedTags) {
                    if (cit instanceof Tag) {
                        Tag citt = (Tag) cit;
                        citt.getTimelined().removeTag(citt);
                    }
                }
                ct.getTimelined().removeTag(ct);
                swf.computeDependentCharacters();
                removeCharacters(needed, swf);
            }
        }
    }

    /**
     * Imports sprite from GIF image.
     *
     * @param spriteTag Sprite tag
     * @param is Input stream
     * @return True if import was successful
     * @throws IOException On I/O error
     */
    public boolean importSprite(DefineSpriteTag spriteTag, InputStream is) throws IOException {
        throw new IOException("GIF sprite import is not included in the StrikeGod Engine FFDEC build");
    }

    /**
     * Bulk import sprites from directory.
     * @param spritesDir Directory with sprites
     * @param swf SWF
     * @param printOut Print out
     * @return Number of imported sprites
     */
    public int bulkImport(File spritesDir, SWF swf, boolean printOut) {
        Map<Integer, CharacterTag> characters = swf.getCharacters(false);
        int spriteCount = 0;
        List<String> extensions = Arrays.asList("gif");
        File[] allFiles = spritesDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                String nameLower = name.toLowerCase();
                for (String ext : extensions) {
                    if (nameLower.endsWith("." + ext)) {
                        return true;
                    }
                }
                return false;
            }
        });
        for (int characterId : characters.keySet()) {
            CharacterTag tag = characters.get(characterId);
            if (tag instanceof DefineSpriteTag) {
                DefineSpriteTag spriteTag = (DefineSpriteTag) tag;
                List<File> existingFilesForSpriteTag = new ArrayList<>();

                List<String> classNameExpectedFileNames = new ArrayList<>();
                for (String className : spriteTag.getClassNames()) {
                    classNameExpectedFileNames.add(Helper.makeFileName(className));
                }

                for (File f : allFiles) {
                    if (f.getName().startsWith("" + characterId + ".")
                            || f.getName().startsWith("" + characterId + "_")) {
                        existingFilesForSpriteTag.add(f);
                    } else {
                        String nameNoExt = f.getName();
                        if (nameNoExt.contains(".")) {
                            nameNoExt = nameNoExt.substring(0, nameNoExt.lastIndexOf("."));
                        }
                        if (classNameExpectedFileNames.contains(nameNoExt)) {
                            existingFilesForSpriteTag.add(f);
                        }
                    }
                }
                existingFilesForSpriteTag.sort(new Comparator<File>() {
                    @Override
                    public int compare(File o1, File o2) {
                        String ext1 = o1.getName().substring(o1.getName().lastIndexOf(".") + 1);
                        String ext2 = o2.getName().substring(o2.getName().lastIndexOf(".") + 1);
                        int ret = extensions.indexOf(ext1) - extensions.indexOf(ext2);
                        if (ret == 0) {
                            return o1.getName().compareTo(o2.getName());
                        }
                        return ret;
                    }
                });

                if (existingFilesForSpriteTag.isEmpty()) {
                    continue;
                }

                if (existingFilesForSpriteTag.size() > 1) {
                    Logger.getLogger(SpriteImporter.class.getName())
                            .log(
                                    Level.WARNING,
                                    "Multiple matching files for sprite tag {0} exists, {1} selected",
                                    new Object[] {
                                        characterId,
                                        existingFilesForSpriteTag.get(0).getName()
                                    });
                }
                File sourceFile = existingFilesForSpriteTag.get(0);

                if (printOut) {
                    System.out.println("Importing character " + characterId + " from file " + sourceFile.getName());
                }

                try (FileInputStream fis = new FileInputStream(sourceFile.getAbsolutePath())) {
                    importSprite(spriteTag, fis);
                    spriteCount++;
                } catch (IOException ex) {
                    Logger.getLogger(ShapeImporter.class.getName())
                            .log(
                                    Level.WARNING,
                                    "Cannot import sprite " + characterId + " from file " + sourceFile.getName(),
                                    ex);
                }
                if (CancellableWorker.isInterrupted()) {
                    break;
                }
            }
        }
        return spriteCount;
    }
}
