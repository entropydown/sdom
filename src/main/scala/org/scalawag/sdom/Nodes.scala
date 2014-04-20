package org.scalawag.sdom

import scala.annotation.tailrec
import org.scalawag.sdom.output.DefaultOutputter
import org.scalawag.sdom.ScalaXmlConversions.{Strategies,Strategy}

//=====================================================================================================================
// Built nodes
//
// Because XPath and some of the natural methods of Nodes (like parent) prevent immutability (we can't create the
// necessary circular references where the parent refers to child which refers back to parent), the Rooted wrappers
// allow sdom a way to maintain a NodeSpec's ancestry in a dynamic way.  Each node is immutable downward and is not
// navigable upward.  When you execute a query against a NodeSpec and descend to its subnodes, the Rooted wrapper keeps
// track of how you got there so that you can navigate upward from the NodeSpec back into whatever ancestry led you to
// it while still allowing the NodeSpec itself to remain immutable.  This means that you can only navigate upward once
// you have descended but this isn't really much of a restriction in practice.

abstract class Node {
  val parent:Option[Parent]
  val spec:NodeSpec

  def ancestry:Iterable[Parent] = {
    @tailrec
    def helper(n:Node,acc:Iterable[Parent]):Iterable[Parent] =
      n.parent match {
        case None => acc
        case Some(p) => helper(p,acc ++ Iterable(p))
      }

    helper(this,Iterable.empty)
  }

  lazy val asString = DefaultOutputter.output(this)
  override lazy val toString = s"${this.getClass.getSimpleName}($asString)"
}

trait Child extends Node {
  override val spec:ChildSpec
}

trait Named extends Node {
  override val spec:NamedSpec[_] // narrows the parent's definition
  val name = spec.name
  val prefix:String
}

class Parent private[sdom] (override val spec:ParentSpec[_],val parent:Option[Parent]) extends Node with ChildContainer {
  val scope:NamespacesLike = Namespaces.Empty

  lazy val children:Iterable[Child] =
    spec.children.map(descend)

  // It would be more efficient to do the filtering prior to creating the Child to wrap the ChildSpec.  However,
  // this way, we can keep reusing the same set of RootedChildren that we create over and over instead of having
  // to create them anew for each call.  I think this is better.
  //
  // NB: This could be turned into a val if we're going to use this often enough.
  def descendants:Iterable[Child] =
    children.flatMap( c => Iterable(c) ++ ( c match {
      case p:Parent => p.descendants
      case _ => Iterable.empty
    }))

  override def \[T <: Child](selector:Selector[T]):Iterable[T] =
    children.flatMap(selector)

  override def \\[T <: Child](selector:Selector[T]):Iterable[T] =
    descendants.flatMap(selector)

  private[this] def descend(child:ChildSpec) = child match {
    case e:ElementSpec => Element(e,Some(this))
    case t:TextSpec => Text(t,Some(this))
    case c:CDataSpec => CData(c,Some(this))
    case c:CommentSpec => Comment(c,Some(this))
    case p:ProcessingInstructionSpec => ProcessingInstruction(p,Some(this))
  }
}

object Parent {
  def apply[T <: ParentSpec[_]](node:T,parent:Option[Parent]):Parent = node match {
    case d:DocumentSpec => Document(d) // ignore parent
    case e:ElementSpec => Element(e,parent)
  }
}

case class Document(override val spec:DocumentSpec) extends Parent(spec,None) {
  val root = Element(spec.childElements.head,Some(this))
}

object Document {
  def apply(children:ElementSpec*):Document =
    Document(DocumentSpec(children))
  def apply(elem:scala.xml.Elem)(implicit strategy:Strategy = Strategies.Flexible):Document =
    Document(elemToElement(elem))
}

case class Element private[sdom] (override val spec:ElementSpec,override val parent:Option[Parent])
  extends Parent(spec,parent) with Named with Child with AttributeContainer
{
  // These are the namespaces that this Element inherits from its parent.

  private[this] val parentScope = parent.map(_.scope).getOrElse(Namespaces.Empty)

  // Decide what namespace declarations need to be present on this element by looking at the namespace URIs of this
  // element and any of its attributes and then determining which ones require additional namespace declarations.
  //
  // The general approach is to find the best prefix for each Named node here.  Here's the priority that determines
  // which one is the "best":
  //   1. The optional prefix explicitly specified on the Named node.
  //   2. The closest matching existing namespace declaration (including the default namespace).
  //   3. A new default namespace declaration, which we will have to add - ElementSpec only, Attributes don't use
  //      the default namespace.
  //   4. A new prefixed namespace declaration, which we will have to add.

  // All of the namespaces not explicitly declared on this element but which implicitly exist.

  private[this] val implicitNamespaces =
    // Short circuit the namespace resolution/creation logic if we know that there aren't any namespaces
    // that we need to resolve here.  This is a little faster.
    if ( ! spec.usesNamespaces ) {
      Iterable.empty
    } else {
      // These are all of the namespace mappings that we already know about including ones that were explicitly
      // specified as Namespaces on this element and those that exist on our parent (implicit or explicit).

      val preForcedNamespaces = Namespaces(parentScope,spec.namespaces)

      // A list of all URIs that this element and its attributes require and their optional explicit prefixes.

      val myPrefixesAndURIs = ( spec.attributes.map(a => a.name.uri -> a.prefix ) ++ Iterable(spec.name.uri -> spec.prefix) )

      // These are the declarations that are forced to be introduced by this element and its attributes because
      // they have an explicit prefix which is not already visible and mapped to the correct URI.
      //
      // The validation in ElementSpec ensures that we don't have two conflicting forced namespace declarations required.

      val forcedNamespaces =
        myPrefixesAndURIs filterNot { case (u,p) =>
          // If there is no prefix or the prefix is already mapped to the correct URI, then no forcing is necessary.
          p.isEmpty || preForcedNamespaces.prefixToUri.get(p.get) == Some(u)
        } map { case (u,p) =>
          NamespaceSpec(p.get,u)
        }

      val preElementNamespaces = Namespaces(parentScope,spec.namespaces ++ forcedNamespaces)

      // This is a list of auto-generated prefixes that are undefined in this scope.

      val autoGeneratedPrefixes = {
        val candidatePrefixes = Stream.from(1).map( n => s"ns$n" )
        candidatePrefixes.filterNot(preElementNamespaces.uriToPrefix.contains).iterator
      }

      // The element's namespace is special because, if we don't already have a prefix for its URI, we'll create a
      // default namespace for it, if possible (i.e., it's not already declared on this element) and if there are
      // no descendants in the default namespace (otherwise, things get ugly).

      def hasChildrenWithEmptyURI(e:ElementSpec):Boolean =
        e.name.uri == "" || e.childElements.exists(hasChildrenWithEmptyURI)

      val autoElementNamespace:Option[NamespaceSpec] = {
        val u = spec.name.uri
        if ( preElementNamespaces.uriToPrefix.contains(u) )
          None
        else if ( ! preElementNamespaces.local.prefixToUri.contains("") && ! hasChildrenWithEmptyURI(spec) )
          Some(NamespaceSpec("",u))
        else
          Some(NamespaceSpec(autoGeneratedPrefixes.next,u))
      }

      val preAttributeNamespaces =
        Namespaces(parentScope,spec.namespaces ++ forcedNamespaces ++ autoElementNamespace)

      val autoAttributeNamespaces =
        spec.attributes filterNot { a =>
          a.prefix.isDefined || a.name.uri.isEmpty || preAttributeNamespaces.uriToPrefix.contains(a.name.uri)
        } map { a =>
  //        try {
            NamespaceSpec(autoGeneratedPrefixes.next,a.name.uri)
  //        } catch {
  //          case ex:IllegalArgumentException =>
  //            throw new IllegalArgumentException(s"invalid namespace implied attribute ${a.name} on element ${this.ancestry}: " + ex.getLocalizedMessage)
  //        }
        }

    // All of the namespaces not explicitly declared on this element but which implicitly exist.

    forcedNamespaces ++ autoElementNamespace ++ autoAttributeNamespaces
  }

  // We need to keep track of the ones that were explicit (because these need to be copied when the
  // tree is transformed) v. implicit (because these shouldn't be copied but do need to be serialized/used).

  private[this] val explicitNamespaces = spec.namespaces

  override val scope =
    if ( explicitNamespaces.isEmpty && implicitNamespaces.isEmpty )
      parentScope
    else
      Namespaces(parentScope,explicitNamespaces ++ implicitNamespaces)

  val namespaces = {
    val explicitNamespaceNodes = explicitNamespaces map { ns =>
      Namespace(ns,true,Some(this))
    }
    val implicitNamespaceNodes = implicitNamespaces map { ns =>
      Namespace(ns,false,Some(this))
    }
    explicitNamespaceNodes ++ implicitNamespaceNodes
  }

  // The scope should now have the optimal prefix for all the URIs that we need to represent.

  val prefix = scope.uriToPrefix(spec.name.uri)

  val attributes:Iterable[Attribute] =
    spec.attributes map { a =>
      // The empty prefix always means the empty URI for attributes (they don't use the default namespace).
      val prefix = a.prefix.getOrElse {
        a.name.uri match {
          case "" => ""
          case u => scope.uriToPrefix(u)
        }
      }
      Attribute(a,prefix,Some(this))
    }

  val text = spec.text

  val attributeMap:Map[AttributeName,Attribute] =
    attributes.map( a => a.spec.name -> a ).toMap

  def \@(selector:Selector[Attribute]):Iterable[Attribute] =
    attributes.flatMap(selector)
}

case class Attribute private[sdom] (override val spec:AttributeSpec,prefix:String,parent:Option[Element])
  extends Node with Named
{
  val value = spec.value
}

case class Namespace private[sdom] (override val spec:NamespaceSpec,explicit:Boolean,parent:Option[Element])
  extends Node
{
  val prefix = spec.prefix
  val uri = spec.uri
}

abstract class TextLike private[sdom] (override val spec:TextLikeSpec,parent:Option[Parent])
  extends Node with Child
{
  val text = spec.text
}

case class CData private[sdom] (override val spec:CDataSpec,override val parent:Option[Parent])
  extends TextLike(spec,parent)

case class Text private[sdom] (override val spec:TextSpec,override val parent:Option[Parent])
  extends TextLike(spec,parent)

case class ProcessingInstruction private[sdom] (spec:ProcessingInstructionSpec,parent:Option[Parent])
  extends Node with Child
{
  val target = spec.target
  val data = spec.data
}

case class Comment private[sdom] (override val spec:CommentSpec,parent:Option[Parent])
  extends Node with Child
{
  val text = spec.text
}

private[sdom] trait ChildContainer {
  def \[T <: Child](selector:Selector[T]):Iterable[T]
  def \\[T <: Child](selector:Selector[T]):Iterable[T]

  // I wanted to do the following with implicit conversions from String -> Selector[Element] but I can't
  // figure out how to make it so that it's non-ambiguous with respect to the implicit conversion from
  // String -> Selector[Attribute] that's needs for \@ below.

  def \(name:ElementName):Iterable[Element] =
    \[Element](Selector( _.name == name ))

  def \\(name:ElementName):Iterable[Element] =
    \\[Element](Selector( _.name == name ))
}

private[sdom] trait AttributeContainer {
  def \@(selector:Selector[Attribute]):Iterable[Attribute]

  // I wanted to do the following with implicit conversions from String -> Selector[Attribute] but I can't
  // figure out how to make it so that it's non-ambiguous with respect to the implicit conversion from
  // String -> Selector[Element] that's needs for \ and \\ above.

  def \@(name:AttributeName):Iterable[Attribute] =
    \@(Selector[Attribute]( _.name == name ))
}

/* sdom -- Copyright 2014 Justin Patterson -- All Rights Reserved */