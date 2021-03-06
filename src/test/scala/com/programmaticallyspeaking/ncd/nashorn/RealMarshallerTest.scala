package com.programmaticallyspeaking.ncd.nashorn

import java.util

import com.programmaticallyspeaking.ncd.host._
import com.programmaticallyspeaking.ncd.host.types.{ExceptionData, ObjectPropertyDescriptor, PropertyDescriptorType, Undefined}
import jdk.nashorn.api.scripting.AbstractJSObject
import org.scalactic.Equality
import org.scalatest.Inside
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.collection.{GenMap, mutable}
import scala.language.higherKinds
import scala.util.Try

class RealMarshallerTest extends RealMarshallerTestFixture with Inside with TableDrivenPropertyChecks {
  import RealMarshallerTest._

  val simpleValues = Table(
    ("desc", "expression", "expected"),
    ("string", "'hello world'", SimpleValue("hello world")),
    ("concatenated string", "'hello ' + 'world'", SimpleValue("hello world")),
    ("concatenated string via argument", "(function (arg) { return 'hello ' + arg;  }).call(null, 'world')", SimpleValue("hello world")),
    ("integer value", "42", SimpleValue(42)),
    ("floating-point value", "42.5", SimpleValue(42.5d)),
    ("actual Java char", "java.lang.Character.valueOf('f')", SimpleValue('f')),
    ("actual Java short", "java.lang.Short.valueOf(42)", SimpleValue(42.asInstanceOf[Short])),
    ("actual Java byte", "java.lang.Byte.valueOf(42)", SimpleValue(42.asInstanceOf[Byte])),
    ("boolean value", "true", SimpleValue(true)),
    ("null", "null", EmptyNode),
    ("undefined", "undefined", SimpleValue(Undefined)),
    ("NaN", "NaN", SimpleValue(Double.NaN))
  )

  def evalArray(expr: String)(handler: (ArrayNode) => Unit): Unit = {
    evaluateExpression(expr) { (host, actual) =>
      inside(actual) {
        case an: ArrayNode => handler(an)
      }
    }
  }

  def evalObject(expr: String)(handler: (ObjectNode) => Unit): Unit = {
    evaluateExpression(expr) { (_, actual) =>
      inside(actual) {
        case on: ObjectNode => handler(on)
      }
    }
  }

  "Marshalling to ValueNode works for" - {
    forAll(simpleValues) { (desc, expr, expected) =>
      desc in {
        evaluateExpression(expr) { (_, actual) =>
          actual should equal (expected)
        }
      }
    }

    "Date" in {
      evaluateExpression("new Date(2017,0,21)") { (_, actual) =>
        inside(actual) {
          case DateNode(str, _) =>
            str should fullyMatch regex "Sat Jan 21 2017 00:00:00 [A-Z]{3}[0-9+]{5} (.*)"
        }
      }
    }

    "actual Java float" in {
      evaluateExpression("java.lang.Float.valueOf(42.5)") { (_, actual) =>
        // Java 8 and Java 9 behave differently.
        inside(actual) {
          case SimpleValue(f: Float) => f should be (42.5f)
          case SimpleValue(d: Double) => d should be (42.5d)
        }
      }
    }

    "Error" in {
      evaluateExpression("new TypeError('oops')") { (_, actual) =>
        inside(actual) {
          case ErrorValue(data, isBasedOnThrowable, _) =>
            val stack = "TypeError: oops\n\tat <program> (<eval>:1)"
            data should be (ExceptionData("TypeError", "oops", 1, -1, "<eval>", Some(stack)))
            isBasedOnThrowable should be (false)
        }
      }
    }

    "thrown Error with 0-based column number" in {
      val expr = "(function(){try{\r\nthrow new Error('error');}catch(e){return e;}})()"

      evaluateExpression(expr) { (_, actual) =>
        inside(actual) {
          case ErrorValue(data, _, _) =>
            data.columnNumberBase0 should be (0)
        }
      }
    }

    "Java Exception" - {
      val expr = "(function(){try{throw new java.lang.IllegalArgumentException('oops');}catch(e){return e;}})()"

      def evalException(handler: (ErrorValue) => Unit): Unit = {
        evaluateExpression(expr) { (_, actual) =>
          inside(actual) {
            case err: ErrorValue => handler(err)
          }
        }
      }

      "with appropriate exception data" in {
        evalException { err =>
          err.data should be (ExceptionData("java.lang.IllegalArgumentException", "oops", 3, -1, "<eval>",
            Some("java.lang.IllegalArgumentException: oops")))
        }
      }
    }

    "RegExp" - {
      // Note: flags 'u' and 'y' are not supported by Nashorn
      // See https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/RegExp
      val expr = "/.*/gim"

      def evalRegexp(handler: (RegExpNode) => Unit): Unit = {
        evaluateExpression(expr) { (_, actual) =>
          inside(actual) {
            case re: RegExpNode => handler(re)
          }
        }
      }

      "with a string representation" in {
        evalRegexp { re =>
          re.stringRepresentation should be ("/.*/gim")
        }
      }
    }

    "JSObject-based array" - {

      val testCases = Table(
        ("description", "class"),
        ("with proper class name", classOf[ClassNameBasedArrayJSObject]),
        ("with isArray==true", classOf[IsArrayBasedArrayJSObject])
      )

      forAll(testCases) { (description, clazz) =>
        description in {
          val expr = s"createInstance('${clazz.getName}')"
          evalArray(expr) { an =>
            an.size should be (2)
          }
        }
      }
    }

    "plain array" - {
      "should not get a class name" in {
        val expr = "[1,2]"
        evalArray(expr) { an =>
          an.typedClassName should be (None)
        }
      }
    }

    "typed array" - {
      val typedArrayCases = Table(
        ("className"),
        ("Int8Array"),
        ("Uint8Array"),
        ("Uint8ClampedArray"),
        ("Int16Array"),
        ("Uint16Array"),
        ("Int32Array"),
        ("Uint32Array"),
        ("Float32Array"),
        ("Float64Array")
      )

      forAll(typedArrayCases) { (className) =>
        s"gets a class name for $className" in {
          val expr = s"new $className([1,2])"
          evalArray(expr) { an =>
            an.typedClassName should be (Some(className))
          }
        }
      }
    }

    "object class name" - {
      val classNameCases = Table(
        ("desc", "expr", "className"),
        ("plain object", "{foo:42}", "Object"),
        ("object with type", "new ArrayBuffer()", "ArrayBuffer"),
        ("Java object", "new java.util.ArrayList()", "java.util.ArrayList"),
        ("JSObject object", s"createInstance('${classOf[ObjectLikeJSObject].getName}')", "Object"),
        ("JS 'class'", s"(function() { return new MyClass(); function MyClass() {} })()", "MyClass"),
        ("DataView", "new DataView(new ArrayBuffer(10))", "DataView")
      )

      forAll(classNameCases) { (desc, expr, className) =>
        s"is set for $desc" in {
          evalObject(expr) { obj =>
            obj.className should be (className)
          }
        }
      }
    }

    "Java array" - {
      "gets correct size" in {
        val expr =
          """(function() {
            |var StringArray = Java.type("java.lang.String[]");
            |var arr = new StringArray(2);
            |arr[0] = "testing";
            |arr[1] = "foobar";
            |return arr;
            |})()
          """.stripMargin
        evalArray(expr) { an =>
          an.size should be (2)
        }
      }
    }

    "JSObject-based function" - {
      def evalFunction(expr: String)(handler: (FunctionNode) => Unit): Unit = {
        evaluateExpression(expr) { (_, actual) =>
          inside(actual) {
            case fn: FunctionNode => handler(fn)
          }
        }
      }

      val testCases = Table(
        ("description", "class", "tester"),
        ("with proper class name", classOf[ClassNameBasedFunctionJSObject],
          (fn: FunctionNode) => {fn.name should be ("")}),
        ("with isFunction==true", classOf[IsFunctionBasedFunctionJSObject],
          (fn: FunctionNode) => {fn.name should be ("")}),
        ("with a name", classOf[WithNameFunctionJSObject],
          (fn: FunctionNode) => {fn.copy(objectId = null) should be (FunctionNode("fun", "function fun() {}", null))})
      )

      forAll(testCases) { (description, clazz, tester) =>
        description in {
          val expr = s"createInstance('${clazz.getName}')"
          evalFunction(expr) { fn =>
            tester(fn)
          }
        }
      }
    }
  }
}

object RealMarshallerTest {
  case class Cycle(objectId: ObjectId)
  def expand(host: ScriptHost, node: ValueNode, includeInherited: Boolean = false, onlyAccessors: Boolean = false, expandProto: Boolean = false): Any = {
    val seenObjectIds = mutable.Set[ObjectId]()
    // Remove the 'class' JavaBean getter because it's everywhere so it's noise.
    def removeProps: ((String, ObjectPropertyDescriptor)) => Boolean = e => {
      if (e._1 == "class") false
      else if (e._1 == "__proto__" && !expandProto) false
      else true
    }
    def recurse(node: ValueNode): Any = node match {
      case complex: ComplexNode if seenObjectIds.contains(complex.objectId) =>
        // In Nashorn, apparently the constructor of the prototype of a function is the function itself...
        Cycle(complex.objectId)
      case scope: ScopeObject =>
        Map("scope" -> true, "name" -> scope.name, "type" -> scope.scopeType.toString)
      case complex: ComplexNode =>
        seenObjectIds += complex.objectId
        host.getObjectProperties(complex.objectId, !includeInherited, onlyAccessors).filter(removeProps).map(e => e._2.descriptorType match {
          case PropertyDescriptorType.Generic =>
            e._1 -> "???"
          case PropertyDescriptorType.Data =>
            e._2.value match {
              case Some(value) => e._1 -> recurse(value)
              case None => throw new RuntimeException("Incorrect data descriptor, no value")
            }
          case PropertyDescriptorType.Accessor =>
            val props = e._2.getter.map(_ => "get").toSeq ++ e._2.setter.map(_ => "set")
            e._1 -> props.map(p => p -> "<function>").toMap
        }).toMap
      case EmptyNode => null
      case SimpleValue(simple) => simple
      case other => throw new Exception("Unhandled: " + other)
    }
    recurse(node)
  }

  implicit val valueNodeEq: Equality[ValueNode] =
    (a: ValueNode, b: Any) => b match {
      case vn: ValueNode =>
        a match {
          case SimpleValue(d: Double) =>
            vn match {
              case SimpleValue(ad: Double) =>
                // Required for NaN comparison
                java.lang.Double.compare(d, ad) == 0
              case other => false
            }
          case _ =>
            a == vn
        }

      case other => false
    }

  /**
    * If [[anyEqWithMapSupport]] is used as implicit `Equality`, this object can appear as a `Map` value (possibly in
    * a nested `Map`) to match any actual value.
    */
  case object AnyObject

  def anyEqWithMapSupport: Equality[Any] = (a: Any, b: Any) => compareAny(a, b)

  private def compareAny(a: Any, b: Any): Boolean = a match {
    case aMap: Map[Any, _] =>
      b match {
        case bMap: Map[Any, _] => compareMaps(aMap, bMap)
        case _ => a == b
      }
    case _ => a == b
  }

  private def compareMaps(a: Map[Any, _], b: Map[Any, _]): Boolean = {
    if (a.size != b.size) return false
    // Go through entries in a and require equality for values
    val bIsOk = a forall { entry =>
      b.get(entry._1) match {
        case Some(AnyObject) => true
        case Some(value) => compareAny(entry._2, value)
        case None => false
      }
    }
    // Return false if b contains an entry that is not in a
    val bHasKeyNotInA = !b.forall(e => a.contains(e._1))
    bIsOk && !bHasKeyNotInA
  }
}

abstract class BaseArrayJSObject(items: Seq[AnyRef]) extends AbstractJSObject {
  import scala.collection.JavaConverters._
  override def hasSlot(slot: Int): Boolean = slot >= 0 && slot < items.size

  override def getSlot(index: Int): AnyRef = items(index)

  override def hasMember(name: String): Boolean = Try(name.toInt).map(hasSlot).getOrElse(name == "length")

  override def getMember(name: String): AnyRef = Try(name.toInt).map(getSlot).getOrElse(if (name == "length") items.size.asInstanceOf[AnyRef] else null)

  override def keySet(): util.Set[String] = (items.indices.map(_.toString) :+ "length").toSet.asJava

  override def values(): util.Collection[AnyRef] = items.asJava
}

class ClassNameBasedArrayJSObject extends BaseArrayJSObject(Seq("a", "b")) {
  override def getClassName: String = "Array"
}

class IsArrayBasedArrayJSObject extends BaseArrayJSObject(Seq("a", "b")) {
  override def isArray: Boolean = true
}

class OnlySlotBasedArrayJSObject extends IsArrayBasedArrayJSObject {
  override def getMember(name: String): AnyRef = if (name == "length") super.getMember("length") else null
}

class SlotBasedArrayJSObjectThatMisbehavesForGetMember extends IsArrayBasedArrayJSObject {
  override def getMember(name: String): AnyRef = {
    if (name == "length") super.getMember("length") else throw new RuntimeException("getMember not supported for: " + name)
  }
}

class ObjectLikeJSObject extends AbstractJSObject {
  import scala.collection.JavaConverters._
  val data: Map[String, AnyRef] = Map("a" -> 42.asInstanceOf[AnyRef], "b" -> 43.asInstanceOf[AnyRef])

  override def values(): util.Collection[AnyRef] = data.values.toList.asJava

  override def hasMember(name: String): Boolean = data.contains(name)

  override def getMember(name: String): AnyRef = data(name)

  override def getClassName: String = "Object"

  override def keySet(): util.Set[String] = data.keySet.asJava
}

abstract class BaseFunctionJSObject extends AbstractJSObject {
  override def call(thiz: scala.Any, args: AnyRef*): AnyRef = "ok"
}

class ClassNameBasedFunctionJSObject extends BaseFunctionJSObject {
  override def getClassName: String = "Function"

  override def call(thiz: scala.Any, args: AnyRef*): AnyRef = "ok"
}

class IsFunctionBasedFunctionJSObject extends BaseFunctionJSObject {
  override def call(thiz: scala.Any, args: AnyRef*): AnyRef = "ok"

  override def isFunction: Boolean = true
}

class WithNameFunctionJSObject extends ClassNameBasedFunctionJSObject {
  override def getMember(name: String): AnyRef = {
    if (name == "name") "fun"
    else super.getMember(name)
  }
}