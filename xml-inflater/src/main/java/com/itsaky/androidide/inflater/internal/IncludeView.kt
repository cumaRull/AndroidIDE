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

package com.itsaky.androidide.inflater.internal

import com.itsaky.androidide.inflater.IAttribute

/**
 * [IView][com.itsaky.androidide.inflater.IView] implementation for `<include>` tags.
 *
 * @author Akash Yadav
 */
class IncludeView(private val embedded: ViewImpl) :
  ViewImpl(embedded.file, embedded.name, embedded.view) {
  
  override val tag: String
    get() = "include"

  override fun applyAttribute(attribute: IAttribute) {
    // The attributes must be applied to the embedded view
    embedded.applyAttribute(attribute)
  }
  
  override fun printHierarchy(): String {
    return embedded.printHierarchy()
  }
  
  override fun printHierarchy(builder: StringBuilder, indent: Int) {
    return embedded.printHierarchy(builder, indent)
  }
}
