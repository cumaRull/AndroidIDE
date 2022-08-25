/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.lsp.xml.providers.completion.layout

import com.android.aaptcompiler.AaptResourceType.STYLEABLE
import com.android.aaptcompiler.ConfigDescription
import com.android.aaptcompiler.ResourceGroup
import com.android.aaptcompiler.ResourcePathData
import com.android.aaptcompiler.ResourceTablePackage
import com.android.aaptcompiler.Styleable
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItem.Companion.matchLevel
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.MatchLevel.NO_MATCH
import com.itsaky.androidide.lsp.xml.providers.completion.AttributeCompletionProvider
import com.itsaky.androidide.lsp.xml.utils.XmlUtils.NodeType
import com.itsaky.androidide.xml.widgets.Widget
import com.itsaky.androidide.xml.widgets.WidgetTable
import org.eclipse.lemminx.dom.DOMDocument
import org.eclipse.lemminx.dom.DOMNode

/**
 * Provides attribute completions in layout files.
 *
 * @author Akash Yadav
 */
class LayoutAttributeCompletionProvider : AttributeCompletionProvider() {

  override fun doComplete(
    params: CompletionParams,
    pathData: ResourcePathData,
    document: DOMDocument,
    type: NodeType,
    prefix: String
  ): CompletionResult {
    val node = document.findNodeAt(params.position.requireIndex())
    val attr = document.findAttrAt(params.position.requireIndex())
    val list = mutableListOf<CompletionItem>()

    val newPrefix =
      if (attr.name.contains(':')) {
        attr.name.substringAfterLast(':')
      } else attr.name

    val namespace =
      attr.namespaceURI
        ?: run {
          return completeFromAllNamespaces(node, list, newPrefix)
        }

    val nsPrefix = attr.nodeName.substringBefore(':')
    completeForNamespace(namespace, nsPrefix, node, newPrefix, list)

    return CompletionResult(list)
  }

  private fun completeFromAllNamespaces(
    node: DOMNode,
    list: MutableList<CompletionItem>,
    newPrefix: String
  ): CompletionResult {
    val namespaces = mutableSetOf<Pair<String, String>>()
    var curr: DOMNode? = node

    @Suppress("SENSELESS_COMPARISON") // attributes might be null. ignore warning
    while (curr != null && curr.attributes != null) {
      for (i in 0 until curr.attributes.length) {
        val currAttr = curr.getAttributeAtIndex(i)
        if (currAttr.isXmlns) {
          namespaces.add(currAttr.localName to currAttr.value)
        }
      }
      curr = curr.parentNode
    }

    namespaces.forEach { completeForNamespace(it.second, it.first, node, newPrefix, list) }

    return CompletionResult(list)
  }

  private fun completeForNamespace(
    namespace: String,
    nsPrefix: String,
    node: DOMNode,
    newPrefix: String,
    list: MutableList<CompletionItem>
  ) {

    val tables = findResourceTables(namespace)
    if (tables.isEmpty()) {
      return
    }

    val pck = namespace.substringAfter(NAMESPACE_PREFIX)
    val packages = mutableSetOf<ResourceTablePackage>()
    for (table in tables) {
      if (namespace == NAMESPACE_AUTO) {
        packages.addAll(table.packages.filter { it.name.isNotBlank() })
      } else {
        val tablePackage = table.findPackage(pck)
        tablePackage?.also { packages.add(it) }
      }
    }

    for (tablePackage in packages) {
      addFromPackage(tablePackage, node, tablePackage.name, nsPrefix, newPrefix, list)
    }
  }

  private fun addFromPackage(
    tablePackage: ResourceTablePackage?,
    node: DOMNode,
    pck: String,
    nsPrefix: String,
    newPrefix: String,
    list: MutableList<CompletionItem>
  ) {
    val styleables = tablePackage?.findGroup(STYLEABLE) ?: return
    val nodeStyleables = findNodeStyleables(node, styleables)
    if (nodeStyleables.isEmpty()) {
      return
    }

    addFromStyleables(
      styleables = nodeStyleables,
      pck = pck,
      pckPrefix = nsPrefix,
      prefix = newPrefix,
      list = list
    )
  }

  private fun addFromStyleables(
    styleables: Set<Styleable>,
    pck: String,
    pckPrefix: String,
    prefix: String,
    list: MutableList<CompletionItem>
  ) {
    for (nodeStyleable in styleables) {
      for (ref in nodeStyleable.entries) {
        val matchLevel = matchLevel(ref.name.entry!!, prefix)
        if (matchLevel == NO_MATCH) {
          continue
        }
        list.add(
          createAttrCompletionItem(
            attr = ref,
            resPkg = pck,
            nsPrefix = pckPrefix,
            matchLevel = matchLevel
          )
        )
      }
    }
  }

  private fun findNodeStyleables(node: DOMNode, styleables: ResourceGroup): Set<Styleable> {
    val nodeName = node.nodeName
    val widgets = Lookup.DEFAULT.lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return emptySet()

    // Find the widget
    val widget =
      if (nodeName.contains(".")) {
        widgets.getWidget(nodeName)
      } else {
        widgets.findWidgetWithSimpleName(nodeName)
      }

    if (widget != null) {
      // This is a widget from the Android SDK
      // we can get its superclasses and other stuff
      return findStyleablesForWidget(styleables, widgets, widget, node)
    } else if (nodeName.contains('.')) {
      // Probably a custom view or a view from libraries
      // If the developer follows the naming convention then only the completions will be provided
      // This must be called if and only if the tag name is qualified
      return findStyleablesForName(styleables, node)
    }

    return emptySet()
  }

  private fun findStyleablesForName(
    styleables: ResourceGroup,
    node: DOMNode,
    addFromParent: Boolean = false,
    suffix: String = ""
  ): Set<Styleable> {
    val result = mutableSetOf<Styleable>()

    // Styles must be defined by the View class' simple name
    var name = node.nodeName
    if (name.contains('.')) {
      name = name.substringAfterLast('.')
    }

    // Common attributes for all views
    addWidgetStyleable(styleables, "View", result)

    // Find the declared styleable
    val entry = findStyleableEntry(styleables, "$name$suffix")
    if (entry != null) {
      result.add(entry)
    }

    // If the layout params from the parent must be added, check for parent and then add them
    // Layout param attributes must be added only from the direct parent
    if (addFromParent) {
      node.parentNode?.also { result.addAll(findLayoutParams(styleables, node.parentNode)) }
    }

    return result
  }

  private fun findLayoutParams(styleables: ResourceGroup, parentNode: DOMNode): Set<Styleable> {
    val result = mutableSetOf<Styleable>()

    // Add layout params common for all view groups and the ones supporting child margins
    addWidgetStyleable(styleables, "ViewGroup", result, suffix = "_Layout")
    addWidgetStyleable(styleables, "ViewGroup", result, suffix = "_MarginLayout")

    var name = parentNode.nodeName
    if (name.contains('.')) {
      name = name.substringAfterLast('.')
    }

    addWidgetStyleable(styleables, name, result, "_Layout")

    return result
  }

  private fun findStyleablesForWidget(
    styleables: ResourceGroup,
    widgets: WidgetTable,
    widget: Widget,
    node: DOMNode,
    adddFromParent: Boolean = true,
    suffix: String = ""
  ): Set<Styleable> {
    val result = mutableSetOf<Styleable>()

    // Find the <declare-styleable> for the widget in the resource group
    addWidgetStyleable(styleables, widget, result, suffix = suffix)

    // Find styleables for all the superclasses
    addSuperclassStyleables(styleables, widgets, widget, result, suffix = suffix)

    // Add attributes provided by the layout params
    if (adddFromParent && node.parentNode != null) {
      val parentName = node.parentNode.nodeName
      val parentWidget =
        if (parentName.contains(".")) {
          widgets.getWidget(parentName)
        } else {
          widgets.findWidgetWithSimpleName(parentName)
        }

      if (parentWidget != null) {
        result.addAll(
          findStyleablesForWidget(
            styleables,
            widgets,
            parentWidget,
            node.parentNode,
            false,
            "_Layout"
          )
        )
      } else {
        result.addAll(findLayoutParams(styleables, node.parentNode))
      }
    }

    return result
  }

  private fun addWidgetStyleable(
    styleables: ResourceGroup,
    widget: Widget,
    result: MutableSet<Styleable>,
    suffix: String = ""
  ) {
    addWidgetStyleable(styleables, widget.simpleName, result, suffix)
  }

  private fun addWidgetStyleable(
    styleables: ResourceGroup,
    widget: String,
    result: MutableSet<Styleable>,
    suffix: String = ""
  ) {
    val entry = findStyleableEntry(styleables, "${widget}${suffix}")
    if (entry != null) {
      result.add(entry)
    }
  }

  private fun addSuperclassStyleables(
    styleables: ResourceGroup,
    widgets: WidgetTable,
    widget: Widget,
    result: MutableSet<Styleable>,
    suffix: String = ""
  ) {
    for (superclass in widget.superclasses) {
      // When a ViewGroup is encountered in the superclasses, add the margin layout params
      if ("android.view.ViewGroup" == superclass) {
        addWidgetStyleable(styleables, "ViewGroup", result, suffix = "_MarginLayout")
      }

      val superr = widgets.getWidget(superclass) ?: continue
      addWidgetStyleable(styleables, superr.simpleName, result, suffix = suffix)
    }
  }

  private fun findStyleableEntry(styleables: ResourceGroup, name: String): Styleable? {
    val value = styleables.findEntry(name)?.findValue(ConfigDescription())?.value
    if (value !is Styleable) {
      return null
    }

    return value
  }
}
