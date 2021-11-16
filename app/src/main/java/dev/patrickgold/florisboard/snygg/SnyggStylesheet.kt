/*
 * Copyright (C) 2021 Patrick Goldinger
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

package dev.patrickgold.florisboard.snygg

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.patrickgold.florisboard.ime.theme.FlorisImeUiSpec
import dev.patrickgold.florisboard.snygg.value.SnyggImplicitInheritValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = SnyggStylesheetSerializer::class)
class SnyggStylesheet(val rules: Map<SnyggRule, SnyggPropertySet>) {
    companion object {
        private const val Unspecified = Int.MIN_VALUE
        private val FallbackPropertySet = SnyggPropertySet(mapOf())
    }

    @Composable
    fun get(
        element: String,
        code: Int = Unspecified,
        group: Int = Unspecified,
        mode: Int = Unspecified,
        isHover: Boolean = false,
        isFocus: Boolean = false,
        isPressed: Boolean = false,
    ) = remember(element, code, group, mode, isHover, isFocus, isPressed) {
        getStatic(element, code, group, mode, isHover, isFocus, isPressed)
    }

    fun getStatic(
        element: String,
        code: Int = Unspecified,
        group: Int = Unspecified,
        mode: Int = Unspecified,
        isHover: Boolean = false,
        isFocus: Boolean = false,
        isPressed: Boolean = false,
    ): SnyggPropertySet {
        val possibleRules = rules.filter { (rule, _) -> rule.element == element }
        possibleRules.keys.find { rule ->
            (code == Unspecified || rule.codes.contains(code))
                && (group == Unspecified || rule.groups.contains(group))
                && (mode == Unspecified || rule.modes.contains(mode))
                && (isHover == rule.hoverSelector)
                && (isFocus == rule.focusSelector)
                && (isPressed == rule.pressedSelector)
        }?.let { return possibleRules[it]!! }
        possibleRules.keys.find { rule ->
            (isHover == rule.hoverSelector)
                && (isFocus == rule.focusSelector)
                && (isPressed == rule.pressedSelector)
        }?.let { return possibleRules[it]!! }
        possibleRules.keys.find { rule ->
            (code == Unspecified || rule.codes.contains(code))
                && (group == Unspecified || rule.groups.contains(group))
                && (mode == Unspecified || rule.modes.contains(mode))
        }?.let { return possibleRules[it]!! }
        return possibleRules.values.firstOrNull() ?: FallbackPropertySet
    }

    operator fun plus(other: SnyggStylesheet): SnyggStylesheet {
        TODO()
    }

    fun edit(): SnyggStylesheetEditor {
        val ruleMap = rules
            .mapKeys { (rule, _) -> rule.edit() }
            .mapValues { (_, propertySet) -> propertySet.edit() }
        return SnyggStylesheetEditor(ruleMap)
    }
}

fun SnyggStylesheet(stylesheetBlock: SnyggStylesheetEditor.() -> Unit): SnyggStylesheet {
    val builder = SnyggStylesheetEditor()
    stylesheetBlock(builder)
    return builder.build()
}

class SnyggStylesheetEditor(initRules: Map<SnyggRuleEditor, SnyggPropertySetEditor>? = null){
    val rules = mutableMapOf<SnyggRuleEditor, SnyggPropertySetEditor>()

    init {
        if (initRules != null) {
            rules.putAll(initRules)
        }
    }

    operator fun String.invoke(
        codes: List<Int> = listOf(),
        groups: List<Int> = listOf(),
        modes: List<Int> = listOf(),
        hoverSelector: Boolean = false,
        focusSelector: Boolean = false,
        pressedSelector: Boolean = false,
        propertySetBlock: SnyggPropertySetEditor.() -> Unit,
    ) {
        val propertySetEditor = SnyggPropertySetEditor()
        propertySetBlock(propertySetEditor)
        val ruleEditor = SnyggRuleEditor(
            element = this,
            codes.toMutableList(),
            groups.toMutableList(),
            modes.toMutableList(),
            hoverSelector,
            focusSelector,
            pressedSelector,
        )
        rules[ruleEditor] = propertySetEditor
    }

    fun build(): SnyggStylesheet {
        val rulesMap = rules
            .mapKeys { (ruleEditor, _) -> ruleEditor.build() }
            .mapValues { (_, propertySetEditor) -> propertySetEditor.build() }
        return SnyggStylesheet(rulesMap)
    }
}

class SnyggStylesheetSerializer : KSerializer<SnyggStylesheet> {
    private val propertyMapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val ruleMapSerializer = MapSerializer(SnyggRule.serializer(), propertyMapSerializer)

    override val descriptor = ruleMapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: SnyggStylesheet) {
        val rawRuleMap = value.rules.mapValues { (_, propertySet) ->
            propertySet.properties.mapValues { (_, snyggValue) ->
                snyggValue.encoder().serialize(snyggValue).getOrThrow()
            }
        }
        ruleMapSerializer.serialize(encoder, rawRuleMap)
    }

    override fun deserialize(decoder: Decoder): SnyggStylesheet {
        val rawRuleMap = ruleMapSerializer.deserialize(decoder)
        val ruleMap = mutableMapOf<SnyggRule, SnyggPropertySet>()
        for ((rule, rawProperties) in rawRuleMap) {
            // FIXME: hardcoding which spec to use, the selection should happen dynamically
            val stylesheetSpec = FlorisImeUiSpec
            val propertySetSpec = stylesheetSpec.propertySetSpec(rule.element) ?: continue
            val properties = rawProperties.mapValues { (name, value) ->
                val propertySpec = propertySetSpec.propertySpec(name)
                if (propertySpec != null) {
                    for (encoder in propertySpec.encoders) {
                        encoder.deserialize(value).onSuccess { snyggValue ->
                            return@mapValues snyggValue
                        }
                    }
                }
                return@mapValues SnyggImplicitInheritValue
            }
            ruleMap[rule] = SnyggPropertySet(properties)
        }
        return SnyggStylesheet(ruleMap)
    }
}
