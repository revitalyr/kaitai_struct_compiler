package io.kaitai.struct.translators

import io.kaitai.struct.format._
import io.kaitai.struct.datatype._
import io.kaitai.struct.datatype.DataType._
import io.kaitai.struct.exprlang.Ast
import io.kaitai.struct.exprlang.Ast.expr
import io.kaitai.struct.format.{Identifier, IoIdentifier, ParentIdentifier, RootIdentifier}
import io.kaitai.struct.languages.RustCompiler
import io.kaitai.struct.{ClassTypeProvider, RuntimeConfig, Utils}

class RustTranslator(provider: TypeProvider, config: RuntimeConfig)
  extends BaseTranslator(provider) {

  import RustCompiler._

  override def doByteArrayLiteral(arr: Seq[Byte]): String =
    "&vec![" + arr.map(x => "%0#2xu8".format(x & 0xff)).mkString(", ") + "]"
  override def doByteArrayNonLiteral(elts: Seq[Ast.expr]): String =
    "&vec![" + elts.map(translate).mkString(", ") + "]"
  override def doArrayLiteral(t: DataType, value: Seq[Ast.expr]): String = {
    t match {
      case CalcStrType => "vec![" + value.map(v => translate(v)).mkString(".to_string(), ") + ".to_string()]"
      case _ => "vec![" + value.map(v => translate(v)).mkString(", ") + "]"
    }
  }

  override val asciiCharQuoteMap: Map[Char, String] = Map(
    '\t' -> "\\t",
    '\n' -> "\\n",
    '\r' -> "\\r",
    '"' -> "\\\"",
    '\\' -> "\\\\"
  )

  override def strLiteralGenericCC(code: Char): String =
    strLiteralUnicode(code)

  override def strLiteralUnicode(code: Char): String =
    "\\u{%x}".format(code.toInt)

  def isSignedIntType(dt: DataType): Boolean = dt match {
    case Int1Type(true) => true
    case IntMultiType(true, _, _) => true
    case CalcIntType => true
    case _ => false
  }

  override def numericBinOp(left: Ast.expr,
                            op: Ast.operator,
                            right: Ast.expr): String = {
    val lt = detectType(left)
    val rt = detectType(right)

    if (isSignedIntType(lt) && isSignedIntType(rt) && op == Ast.operator.Mod)
        s"modulo(${translate(left)} as i64, ${translate(right)} as i64)"
    else {
      if (lt != rt) {
        val ct = RustCompiler.kaitaiPrimitiveToNativeType(TypeDetector.combineTypes(lt, rt))
        s"((${translate(left)} as $ct) ${binOp(op)} (${translate(right)} as $ct))"
      } else {
        super.numericBinOp(left, op, right)
      }
    }
  }

  override def doName(s: String): String = s match {
    case Identifier.PARENT => s
    case _ =>
      val topClass = get_top_class(provider.nowClass)
      val instance_found = get_instance(topClass, s)
      if (instance_found.isDefined) {
        s"$s(${privateMemberName(IoIdentifier)}, ${privateMemberName(RootIdentifier)})?"
      } else {
        s"$s()"
      }
  }

  def findMember(attrName: String): Option[MemberSpec] = {
    def findInClass(inClass: ClassSpec): Option[MemberSpec] = {
      inClass.seq.foreach { el =>
        if (el.id == NamedIdentifier(attrName))
          return Some(el)
      }

      inClass.params.foreach { el =>
        if (el.id == NamedIdentifier(attrName))
          return Some(el)
      }

      val inst = inClass.instances.get(InstanceIdentifier(attrName))
      if (inst.isDefined)
        return inst

      inClass.types.foreach{ t =>
        val found = findInClass(t._2)
        if (found.isDefined)
          return found
      }

      None
    }

    val ms = findInClass(provider.nowClass)
    if (ms.isDefined)
      return ms

    provider.asInstanceOf[ClassTypeProvider].allClasses.foreach { cls =>
      val ms = findInClass(cls._2)
      if (ms.isDefined)
        return ms
    }

    None
  }

  def get_top_class(c: ClassSpec): ClassSpec = c.upClass match {
    case Some(upClass) => get_top_class(upClass)
    case None => c
  }

  def get_instance(cs: ClassSpec, s: String): Option[InstanceSpec] = {
    var found : Option[InstanceSpec] = None
    // look for instance
    cs.instances.foreach { case (instName, instSpec) =>
      if (idToStr(instName) == s) {
        found = Some(instSpec)
      }
    }
    // look deeper
    if (found.isEmpty) {
      cs.types.foreach {
        case (_, typeSpec) =>
          found = get_instance(typeSpec, s)
          if (found.isDefined) {
            return found
          }
        }
    }
    found
  }

  override def anyField(value: expr, attrName: String): String = {
    val t = translate(value)
    val a = doName(attrName)
    var r = ""

    val attr = findMember(attrName)
    if (attr.isDefined) {
      val attrType = attr.get.dataTypeComposite
      attrType match {
        case _: NumericType =>
          r = s"*$t.$a"
        case _: UserTypeInstream =>
          r = s"$t.$a.as_deref().unwrap()"
        case _ =>
          if (need_deref(attrName)) {
            if (t.charAt(0) == '*') {
              r = s"$t.$a"
            } else {
              r = s"*$t.$a"
            }
          } else {
            if (t.charAt(0) == '*') {
              r = s"${t.substring(1)}.$a"
            } else {
              r = s"$t.$a"
            }
          }
      }
    }

    attrName match {
      case Identifier.IO =>
        r = r.replace("()._io()", "_raw()")
      case Identifier.PARENT =>
        // handle _parent._parent
        r = r.replace(".peek()._parent", ".pop().peek()")
      case _ =>
    }
    r
  }

  def rem_vec_amp(s: String): String = {
    if (s.startsWith("&vec!")) {
      s.substring(1)
    } else {
      s
    }
  }

  def remove_deref(s: String): String = {
    if (s.charAt(0) == '*') {
      s.substring(1)
    } else {
      s
    }
  }

  def ensure_deref(s: String): String = {
    if (s.startsWith("self")) {
      s"*$s"
    } else {
      s
    }
  }

  def get_attr(cs: ClassSpec, id: String): Option[MemberSpec] = {
    var found : Option[MemberSpec] = None
    cs.seq.foreach { el =>
      if (idToStr(el.id) == id) {
        found = Some(el)
      }
    }
    // look deeper
    if (found.isEmpty) {
      cs.types.foreach {
        case (_, typeSpec) =>
          found = get_attr(typeSpec, id)
          if (found.isDefined) {
            return found
          }
        }
    }
    found
  }

  def get_param(cs: ClassSpec, id: String): Option[MemberSpec] = {
    var found : Option[MemberSpec] = None
    cs.params.foreach { el =>
      if (idToStr(el.id) == id) {
        found = Some(el)
      }
    }
    // look deeper
    if (found.isEmpty) {
      cs.types.foreach {
        case (_, typeSpec) =>
          found = get_param(typeSpec, id)
          if (found.isDefined) {
            return found
          }
        }
    }
    found
  }

  var context_need_deref_attr = false

  def is_copy_type(dataType: DataType): Boolean = dataType match {
    case _: SwitchType => false
    case _: UserType => false
    case _: BytesType => false
    case _: ArrayType => false
    case _: StrType => false
    case _: EnumType => false
    case _ => true
  }

  def need_deref(s: String): Boolean = {
    var deref = false
    var found = get_attr(get_top_class(provider.nowClass), s)
    if (found.isDefined ) {
      deref = is_copy_type(found.get.dataTypeComposite)
    } else {
      found = get_instance(get_top_class(provider.nowClass), s)
      if (found.isDefined) {
        deref = true //is_copy_type(found.get.dataTypeComposite)
      } else {
        found = get_param(get_top_class(provider.nowClass), s)
        if (found.isDefined) {
          deref = true
        } else {
          deref = false
        }
      }
    }
    deref
  }

  override def doLocalName(s: String): String = s match {
    case Identifier.ITERATOR => "_tmpa"
    case Identifier.ITERATOR2 => "_tmpb"
    case Identifier.INDEX => "_i"
    case Identifier.IO => s"${RustCompiler.privateMemberName(IoIdentifier)}"
    case Identifier.ROOT => s"${RustCompiler.privateMemberName(RootIdentifier)}.ok_or(KError::MissingRoot)?"
    case Identifier.PARENT => s"${RustCompiler.privateMemberName(ParentIdentifier)}.as_ref().unwrap().peek()"
    case _ =>
      val n = doName(s)
      val deref = need_deref(s)
      if (context_need_deref_attr || deref) {
        s"*self.$n"
      } else {
        s"self.$n"
      }
  }
  override def doEnumCompareOp(left: Ast.expr, op: Ast.cmpop, right: Ast.expr): String = {
    context_need_deref_attr = true
    val code = s"${translate(left)} ${cmpOp(op)} ${translate(right)}"
    context_need_deref_attr = false
    code
  }

  override def doInternalName(id: Identifier): String =
    s"${doLocalName(idToStr(id))}"

  override def doEnumByLabel(enumTypeAbs: List[String], label: String): String =
    s"${RustCompiler.types2class(enumTypeAbs)}::${Utils.upperCamelCase(label)}"

  override def doStrCompareOp(left: Ast.expr, op: Ast.cmpop, right: Ast.expr): String = {
    s"${ensure_deref(translate(left))} ${cmpOp(op)} ${remove_deref(translate(right))}.to_string()"
  }

  override def doEnumById(enumTypeAbs: List[String], id: String): String =
    s"($id as i64).try_into()?"

  override def arraySubscript(container: expr, idx: expr): String =
    s"${remove_deref(translate(container))}[${translate(idx)} as usize]"

  override def doIfExp(condition: expr, ifTrue: expr, ifFalse: expr): String = {
    var to_type = ""
    detectType(ifTrue) match {
      case _: UserType => to_type = ".clone()"
      case _: StrType => to_type = ".to_string()"
      case _: BytesType => to_type = ".to_vec()"
      case _ =>
    }
    if (to_type.isEmpty) {
      s"if ${translate(condition)} { ${translate(ifTrue)} } else { ${translate(ifFalse)} }"
    } else {
      s"if ${translate(condition)} { ${remove_deref(translate(ifTrue))}$to_type } else { ${remove_deref(translate(ifFalse))}$to_type }"
    }
  }
  override def translate(v: Ast.expr): String = {
    v match {
      case Ast.expr.EnumById(enumType, id, inType) =>
        id match {
          case ifExp: Ast.expr.IfExp =>
            val enumSpec = provider.resolveEnum(inType, enumType.name)
            val enumName = RustCompiler.types2class(enumSpec.name)
            def toStr(ex: Ast.expr) = ex match {
              case Ast.expr.IntNum(n) => s"$enumName::try_from($n)?"
              case _ => super.translate(ex)
            }
            val ifTrue = toStr(ifExp.ifTrue)
            val ifFalse = toStr(ifExp.ifFalse)

            "if " + translate(ifExp.condition) + s" { $ifTrue } else { $ifFalse }"
          case _ => super.translate(v)
        }
      case _ =>
        super.translate(v)
    }
  }

  // Predefined methods of various types
  override def strConcat(left: Ast.expr, right: Ast.expr): String =
    s"""format!("{}{}", ${translate(left)}, ${translate(right)})"""

  override def strToInt(s: expr, base: expr): String =
    translate(base) match {
      case "10" =>
        s"${translate(s)}.parse::<i32>().unwrap()"
      case _ =>
        s"i32::from_str_radix(${translate(s)}, ${translate(base)}).unwrap()"
    }

  override def enumToInt(v: expr, et: EnumType): String =
    s"i64::from(${remove_deref(translate(v))})"

  override def boolToInt(v: expr): String =
    s"(${translate(v)}) as i32"

  override def floatToInt(v: expr): String =
    s"${translate(v)} as i32"

  override def intToStr(i: expr, base: expr): String = {
    val baseStr = translate(base)
    baseStr match {
      case "10" =>
         s"${remove_deref(translate(i))}.to_string()"
      case _ =>
        s"base_convert(strval(${translate(i)}), 10, $baseStr)"
    }
  }
  override def bytesToStr(bytesExpr: String, encoding: Ast.expr): String = {
    if (bytesExpr.charAt(0) == '*') {
      s"decode_string(&$bytesExpr, &${translate(encoding)})?"
    } else {
      s"decode_string($bytesExpr, &${translate(encoding)})?"
    }
  }

  override def bytesLength(b: Ast.expr): String =
    s"${remove_deref(translate(b))}.len()"
  override def strLength(s: expr): String =
    s"${remove_deref(translate(s))}.len()"
  override def strReverse(s: expr): String = {
    val e = translate(s)
    if (e.charAt(0) == '*')
      s"reverse_string(&$e)?"
    else
      s"reverse_string($e)?"
  }
  override def strSubstring(s: expr, from: expr, to: expr): String =
    s"${translate(s)}[${translate(from)}..${translate(to)}]"

  override def arrayFirst(a: expr): String =
    s"${ensure_deref(translate(a))}.first().ok_or(KError::EmptyIterator)?"
  override def arrayLast(a: expr): String =
    s"${ensure_deref(translate(a))}.last().ok_or(KError::EmptyIterator)?"
  override def arraySize(a: expr): String =
    s"${remove_deref(translate(a))}.len()"

  def is_float_type(a: Ast.expr): Boolean = {
    detectType(a) match {
      case t: CalcArrayType =>
        t.elType match {
          case f: FloatMultiType => true
          case CalcFloatType => true
          case _ => false
        }
      case t: ArrayType =>
        t.elType match  {
          case f: FloatMultiType => true
          case _ => false
        }
      case _ => false
    }
  }

  override def arrayMin(a: Ast.expr): String = {
    if (is_float_type(a)) {
      s"${ensure_deref(translate(a))}.iter().reduce(|a, b| if (a.min(*b)) == *b {b} else {a}).ok_or(KError::EmptyIterator)?"
    } else {
      s"${ensure_deref(translate(a))}.iter().min().ok_or(KError::EmptyIterator)?"
    }
  }

  override def arrayMax(a: Ast.expr): String = {
    if (is_float_type(a)) {
      s"${ensure_deref(translate(a))}.iter().reduce(|a, b| if (a.max(*b)) == *b {b} else {a}).ok_or(KError::EmptyIterator)?"
    } else {
      s"${ensure_deref(translate(a))}.iter().max().ok_or(KError::EmptyIterator)?"
    }
  }
}
