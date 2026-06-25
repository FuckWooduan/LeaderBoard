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
package com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.swf5;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.BaseLocalData;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWF;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWFInputStream;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SWFOutputStream;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.Action;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.LocalDataArea;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.StoreTypeAction;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.as2.Trait;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.CompoundableBinaryOpAs12;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.ConstantPool;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.DecrementActionItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.DirectValueActionItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.EnumeratedValueActionItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.IncrementActionItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.PostDecrementActionItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.PostIncrementActionItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.StoreRegisterActionItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.model.TemporaryRegister;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.parser.ActionParseException;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.parser.pcode.FlasmLexer;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.action.swf4.RegisterNumber;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.types.annotations.SWFVersion;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.GraphSourceItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.GraphSourceItemPos;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.GraphTargetItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.SecondPassData;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.TranslateStack;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.model.AnyItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.model.CompoundableBinaryOp;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * StoreRegister action - Store value in register.
 *
 * @author JPEXS
 */
@SWFVersion(from = 5)
public class ActionStoreRegister extends Action implements StoreTypeAction {

    /**
     * Register number
     */
    public int registerNumber;

    @Override
    public boolean execute(LocalDataArea lda) {
        if (!lda.stackHasMinSize(1)) {
            return false;
        }

        Object value = lda.pop();
        lda.localRegisters.put(registerNumber, value);
        return true;
    }

    /**
     * Constructor.
     *
     * @param registerNumber Register number
     * @param charset Charset
     */
    public ActionStoreRegister(int registerNumber, String charset) {
        super(0x87, 1, charset);
        this.registerNumber = registerNumber;
    }

    /**
     * Constructor.
     *
     * @param actionLength Action length
     * @param sis SWF input stream
     * @throws IOException On I/O error
     */
    public ActionStoreRegister(int actionLength, SWFInputStream sis) throws IOException {
        super(0x87, actionLength, sis.getCharset());
        registerNumber = sis.readUI8("registerNumber");
    }

    /**
     * Constructor.
     *
     * @param lexer Flasm lexer
     * @param charset Charset
     * @throws IOException On I/O error
     * @throws ActionParseException On action parse error
     */
    public ActionStoreRegister(FlasmLexer lexer, String charset) throws IOException, ActionParseException {
        super(0x87, 0, charset);
        registerNumber = (int) lexLong(lexer);
    }

    @Override
    protected void getContentBytes(SWFOutputStream sos) throws IOException {
        sos.writeUI8(registerNumber);
    }

    /**
     * Gets the length of action converted to bytes
     *
     * @return Length
     */
    @Override
    protected int getContentBytesLength() {
        return 1;
    }

    @Override
    public String toString() {
        return "StoreRegister " + registerNumber;
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
        GraphTargetItem value = stack.pop();
        RegisterNumber rn = new RegisterNumber(registerNumber);
        if (regNames.containsKey(registerNumber)) {
            rn.name = regNames.get(registerNumber);
        }
        value.getMoreSrc().add(new GraphSourceItemPos(this, 0));
        if (variables.containsKey("__register" + registerNumber)) {
            if (variables.get("__register" + registerNumber) instanceof TemporaryRegister) {
                variables.remove("__register" + registerNumber);
            }
        }
        boolean define = !variables.containsKey("__register" + registerNumber);
        if (regNames.containsKey(registerNumber)) {
            define = false;
        }

        if (value instanceof TemporaryRegister) {
            // if (((TemporaryRegister) value).value instanceof EnumerationAssignmentValueActionItem) {
            value = ((TemporaryRegister) value).value;
            // }
        }

        variables.put("__register" + registerNumber, value);
        if (value instanceof DirectValueActionItem) {
            if (((DirectValueActionItem) value).value instanceof RegisterNumber) {
                if (((RegisterNumber) ((DirectValueActionItem) value).value).number == registerNumber) {
                    stack.push(value);
                    return;
                }
            }
        }
        if (value instanceof StoreRegisterActionItem) {
            if (((StoreRegisterActionItem) value).register.number == registerNumber) {
                stack.push(value);
                return;
            }
        }

        if (value instanceof IncrementActionItem) {
            GraphTargetItem obj = ((IncrementActionItem) value).object;
            if (!stack.isEmpty() && stack.peek().valueEquals(obj)) {
                stack.pop();
                stack.push(new PostIncrementActionItem(this, lineStartAction, obj));
                stack.push(new AnyItem()); // to avoid leaving popped item on output
                return;
            }
        }
        if (value instanceof DecrementActionItem) {
            GraphTargetItem obj = ((DecrementActionItem) value).object;
            if (!stack.isEmpty() && stack.peek().valueEquals(obj)) {
                stack.pop();
                stack.push(new PostDecrementActionItem(this, lineStartAction, obj));
                stack.push(new AnyItem()); // to avoid leaving popped item on output
                return;
            }
        }

        if ((value instanceof EnumeratedValueActionItem)) {
            // variables.put("__register" + registerNumber, new TemporaryRegister(registerNumber, new
            // EnumerationAssignmentValueActionItem()));
            // variables.put("__register" + registerNumber, null);
            variables.remove("__register" + registerNumber);
        }
        StoreRegisterActionItem ret = new StoreRegisterActionItem(this, lineStartAction, rn, value, define);
        if (value.getNotCoercedNoDup() instanceof CompoundableBinaryOpAs12) {
            CompoundableBinaryOp binaryOp = (CompoundableBinaryOp) value.getNotCoercedNoDup();
            if (binaryOp.getLeftSide() instanceof DirectValueActionItem) {
                DirectValueActionItem directValue = (DirectValueActionItem) binaryOp.getLeftSide();
                if (directValue.value instanceof RegisterNumber) {
                    if (((RegisterNumber) directValue.value).number == registerNumber) {
                        ret.setCompoundValue(binaryOp.getRightSide());
                        ret.setCompoundOperator(binaryOp.getOperator());
                    }
                }
            }
        }

        stack.push(ret);
    }

    @Override
    public int getStackPopCount(BaseLocalData localData, TranslateStack stack) {
        return 1;
    }

    @Override
    public int getStackPushCount(BaseLocalData localData, TranslateStack stack) {
        return 1;
    }

    @Override
    public String getVariableName(Set<String> usedDeobfuscations, TranslateStack stack, ConstantPool cpool, SWF swf) {
        return "__register" + registerNumber;
    }
}
