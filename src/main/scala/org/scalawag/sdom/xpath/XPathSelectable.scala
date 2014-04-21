package org.scalawag.sdom.xpath

import org.scalawag.sdom._

trait XPathSelectable {
  def %<(xpathExpression:String)(implicit namespaces:NamespacesLike = Namespaces.Empty):Iterable[Element] =
    %<(XPath.elements(xpathExpression))

  def %<(expr:XPath[Element]):Iterable[Element] =
    %(expr).map(_.asInstanceOf[Element])

  def %@(xpathExpression:String)(implicit namespaces:NamespacesLike = Namespaces.Empty):Iterable[Attribute] =
    %@(XPath.attributes(xpathExpression))

  def %@(expr:XPath[Attribute]):Iterable[Attribute] =
    %(expr).map(_.asInstanceOf[Attribute])

  def %%(xpathExpression:String)(implicit namespaces:NamespacesLike = Namespaces.Empty):Iterable[Node] =
    %%(XPath.nodes(xpathExpression))

  def %%(expr:XPath[Node]):Iterable[Node] =
    %(expr).map(_.asInstanceOf[Node])

  def %(xpathExpression:String)(implicit namespaces:NamespacesLike = Namespaces.Empty):Iterable[Any] =
    %(XPath(xpathExpression))

  def %[T](expr:XPath[T]):Iterable[T]
}

/* sdom -- Copyright 2014 Justin Patterson -- All Rights Reserved */
