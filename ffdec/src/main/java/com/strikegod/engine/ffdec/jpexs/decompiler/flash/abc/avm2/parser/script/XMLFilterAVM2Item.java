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
package com.strikegod.engine.ffdec.jpexs.decompiler.flash.abc.avm2.parser.script;

import com.strikegod.engine.ffdec.jpexs.decompiler.flash.SourceGeneratorLocalData;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.abc.avm2.model.AVM2Item;
import com.strikegod.engine.ffdec.jpexs.decompiler.flash.helpers.GraphTextWriter;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.CompilationException;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.DottedChain;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.GraphSourceItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.GraphTargetItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.SourceGenerator;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.TypeItem;
import com.strikegod.engine.ffdec.jpexs.decompiler.graph.model.LocalData;
import java.util.List;

/**
 * XML filter.
 *
 * @author JPEXS
 */
public class XMLFilterAVM2Item extends AVM2Item {

    /**
     * Opened namespaces
     */
    public List<NamespaceItem> openedNamespaces;

    /**
     * Object
     */
    public GraphTargetItem object;

    /**
     * Constructor.
     * @param object Object
     * @param value Value
     * @param openedNamespaces Opened namespaces
     */
    public XMLFilterAVM2Item(GraphTargetItem object, GraphTargetItem value, List<NamespaceItem> openedNamespaces) {
        super(null, null, NOPRECEDENCE, value);
        this.openedNamespaces = openedNamespaces;
        this.object = object;
    }

    @Override
    public GraphTextWriter appendTo(GraphTextWriter writer, LocalData localData) throws InterruptedException {
        return null;
    }

    @Override
    public boolean hasReturnValue() {
        return true;
    }

    @Override
    public GraphTargetItem returnType() {
        return new TypeItem(DottedChain.STRING);
    }

    @Override
    public List<GraphSourceItem> toSource(SourceGeneratorLocalData localData, SourceGenerator generator)
            throws CompilationException {
        return ((AVM2SourceGenerator) generator).generate(localData, this);
    }
}
