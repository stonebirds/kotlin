/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen.optimization.captured

import org.jetbrains.kotlin.codegen.optimization.common.OptimizationBasicInterpreter
import org.jetbrains.kotlin.resolve.jvm.AsmTypes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode
import org.jetbrains.org.objectweb.asm.tree.analysis.BasicValue

@Suppress("UNCHECKED_CAST")
abstract class ReferenceTrackingInterpreter<VD : ReferenceValueDescriptor> : OptimizationBasicInterpreter() {
    override fun merge(v: BasicValue, w: BasicValue): BasicValue =
            when {
                v is ProperReferenceValue<*> && w is ProperReferenceValue<*> ->
                    if (v.descriptor == w.descriptor)
                        v
                    else
                        TaintedReferenceValue(
                                getTaintedValueType(v.type, w.type),
                                setOf(v.descriptor as VD, w.descriptor as VD)
                        )

                v is ReferenceValue<*> || w is ReferenceValue<*> ->
                    TaintedReferenceValue(
                            getTaintedValueType(v.type, w.type),
                            v.referenceValueDescriptors + w.referenceValueDescriptors
                    )

                else ->
                    super.merge(v, w)
            }

    private val BasicValue.referenceValueDescriptors: Set<VD>
        get() = if (this is ReferenceValue<*>) this.descriptors as Set<VD> else emptySet<VD>()

    private fun getTaintedValueType(type1: Type?, type2: Type?): Type =
            when {
                type1 == null || type2 == null -> AsmTypes.OBJECT_TYPE
                type1 == type2 -> type1
                else -> AsmTypes.OBJECT_TYPE
            }

    override fun copyOperation(insn: AbstractInsnNode, value: BasicValue): BasicValue? =
            if (value is ReferenceValue<*>) {
                checkRefValuesUsages(insn, listOf(value))
                value
            }
            else {
                super.copyOperation(insn, value)
            }

    override fun unaryOperation(insn: AbstractInsnNode, value: BasicValue): BasicValue? {
        checkRefValuesUsages(insn, listOf(value))
        return super.unaryOperation(insn, value)
    }

    override fun binaryOperation(insn: AbstractInsnNode, value1: BasicValue, value2: BasicValue): BasicValue? {
        checkRefValuesUsages(insn, listOf(value1, value2))
        return super.binaryOperation(insn, value1, value2)
    }

    override fun ternaryOperation(insn: AbstractInsnNode, value1: BasicValue, value2: BasicValue, value3: BasicValue): BasicValue? {
        checkRefValuesUsages(insn, listOf(value1, value2, value3))
        return super.ternaryOperation(insn, value1, value2, value3)
    }

    override fun naryOperation(insn: AbstractInsnNode, values: List<BasicValue>): BasicValue? {
        checkRefValuesUsages(insn, values)
        return super.naryOperation(insn, values)
    }

    protected open fun checkRefValuesUsages(insn: AbstractInsnNode, values: List<BasicValue>) {
        values.forEach { value ->
            if (value is TaintedReferenceValue<*>) {
                value.descriptors.forEach { it.markAsTainted() }
            }
        }

        values.forEachIndexed { pos, value ->
            if (value is ReferenceValue<*>) {
                processRefValueUsage(value, insn, pos)
            }
        }
    }

    protected abstract fun processRefValueUsage(value: ReferenceValue<*>, insn: AbstractInsnNode, position: Int)
}

