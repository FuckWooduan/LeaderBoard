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
package com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.swf4;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.BaseLocalData;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.Action;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.LocalDataArea;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.as2.Trait;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.StringLengthActionItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.types.annotations.SWFVersion;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.GraphSourceItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.GraphTargetItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.SecondPassData;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.TranslateStack;
import com.strikegod.engine.ffdec.jpexs.helpers.utf8.Utf8Helper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * StringLength action - Gets the length of a string.
 *
 * @author JPEXS
 */
@SWFVersion(from = 4)
public class ActionStringLength extends Action {

    /**
     * Constructor.
     */
    public ActionStringLength() {
        super(0x14, 0, Utf8Helper.charsetName);
    }

    @Override
    public String toString() {
        return "StringLength";
    }

    @Override
    public boolean execute(LocalDataArea lda) {
        if (lda.stackIsEmpty()) {
            return false;
        }

        lda.push(StringLengthActionItem.getResult(lda.pop()));
        return true;
    }

    @Override
    public void translate(
            Set<String> usedDeobfuscations,
            Map<String, Map<String, Trait>> uninitializedClassTraits,
            SecondPassData secondPassData,
            boolean insideDoInitAction,
            GraphSourceItem lineStartAction,
            TranslateStack stack,
            List<GraphTargetItem> output,
            HashMap<Integer, String> regNames,
            HashMap<String, GraphTargetItem> variables,
            HashMap<String, GraphTargetItem> functions,
            int staticOperation,
            String path) {
        GraphTargetItem a = stack.pop();
        stack.push(new StringLengthActionItem(this, lineStartAction, a));
    }

    @Override
    public int getStackPopCount(BaseLocalData localData, TranslateStack stack) {
        return 1;
    }

    @Override
    public int getStackPushCount(BaseLocalData localData, TranslateStack stack) {
        return 1;
    }
}
