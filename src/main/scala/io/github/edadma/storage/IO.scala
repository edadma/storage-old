package io.github.edadma.storage

import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.{NoSuchElementException, UUID}
import scala.collection.AbstractIterator
import scala.collection.immutable.ArraySeq
import scala.collection.mutable.{ArrayStack, ListBuffer}

object IO {
  var filetype = "storage"

  private[storage] val pwidth_default = 5
  private[storage] val cwidth_default = 8

  def power_ceil(n: Int): Int =
    require(n >= 0, s"power_ceil: $n is below min input of 0")
    require(n <= 1073741824, s"power_ceil: $n is above max input of 1073741824")

    n match
      case 0 | 1 => 1
      case _ =>
        var x = n - 1
        var res = 2

        while ({ x >>= 1; x != 0 }) res <<= 1

        res

  def timestamp: Instant = Instant.now

  def datetime: OffsetDateTime = OffsetDateTime.now
}

abstract class IO extends IOConstants {
  private[storage] var charset = UTF_8
  private[storage] var pwidth = IO.pwidth_default // pointer width
  private[storage] var cwidth = IO.cwidth_default // cell width
  private[storage] var bucketsPtr: Long = _
  private[storage] var buckets: Array[Long] = _
  private[storage] var uuidOption: Boolean = true

  private[storage] lazy val maxsize = 1L << pwidth * 8
  private[storage] lazy val vwidth = 1 + cwidth // value width
  private[storage] lazy val minblocksize = IO.power_ceil(cwidth + 1) // smallest allocation block needed
  private[storage] lazy val sizeShift = Integer.numberOfTrailingZeros(minblocksize)
  private[storage] lazy val bucketLen = pwidth * 8 - sizeShift

//	println( pwidth, lowestSize, sizeShift, bucketLen )

  //
  // abstract methods
  //

  def close(): Unit

  def force(): Unit

  def readLock(addr: Long): Unit

  def writeLock(addr: Long): Unit

  def readUnlock(addr: Long): Unit

  def writeUnlock(addr: Long): Unit

  def size: Long

  def size_=(l: Long): Unit

  def pos: Long

  def pos_=(p: Long): Unit

  def append: Long

  def getByte: Int

  def putByte(b: Int): Unit

  def getBytes(len: Int): Array[Byte]

  def putBytes(a: Array[Byte]): Unit

  def putBytes(a: Array[Byte], offset: Int, length: Int): Unit

  def getUnsignedByte: Int

  def getChar: Char

  def putChar(c: Char): Unit

  def getShort: Int

  def putShort(s: Int): Unit

  def getUnsignedShort: Int

  def getInt: Int

  def putInt(i: Int): Unit

  def getLong: Long

  def putLong(l: Long): Unit

  def getDouble: Double

  def putDouble(d: Double): Unit

  def writeByteChars(s: String): Unit

  def writeBuffer(buf: MemIO): Unit

  //
  // i/o methods based on abstract methods
  //

  def peekUnsignedByte: Int = {
    val b = getUnsignedByte

    pos -= 1
    b
  }

  def peekUnsignedByte(addr: Long): Int = {
    pos = addr
    peekUnsignedByte
  }

  def getSmall: Int = (getByte << 16) | (getUnsignedByte << 8) | getUnsignedByte

  def putSmall(a: Int): Unit = {
    putByte(a >> 16)
    putByte(a >> 8)
    putByte(a)
  }

  def getBig: Long = {
    var res = 0L

    for (_ <- 1 to pwidth) {
      res <<= 8
      res |= getUnsignedByte
    }

    res
  }

  def putBig(l: Long): Unit = {
    if (l > maxsize)
      sys.error("pointer value too large")

    for (shift <- (pwidth - 1) * 8 to 0 by -8)
      putByte((l >> shift).asInstanceOf[Int])
  }

  def getBig(addr: Long): Long = {
    pos = addr
    getBig
  }

  def putBig(addr: Long, l: Long): Unit = {
    pos = addr
    putBig(l)
  }

  def addBig(a: Long) = {
    val cur = pos
    val sum = getBig + a

    pos = cur
    putBig(sum)
    sum
  }

  def addBig(addr: Long, a: Long): Unit = {
    pos = addr
    addBig(a)
  }

  def getUnsignedByte(addr: Long): Int = {
    pos = addr
    getUnsignedByte
  }

  def getByte(addr: Long): Int = {
    pos = addr
    getByte
  }

  def putByte(addr: Long, b: Int): Unit = {
    pos = addr
    putByte(b)
  }

  def readByteChars(len: Int) = {
    val buf = new StringBuilder

    for (_ <- 1 to len)
      buf += getByte.asInstanceOf[Char]

    buf.toString
  }

  def getByteString: Option[String] = {
    if (remaining >= 1) {
      val len = getUnsignedByte

      if (remaining >= len)
        Some(readByteChars(len))
      else
        None
    } else None
  }

  def putByteString(s: String): Unit = {
    putByte(s.length.asInstanceOf[Byte])
    writeByteChars(s)
  }

  def getString(len: Int, cs: Charset = charset) = new String(getBytes(len), cs)

  def getType: Int =
    getUnsignedByte match {
      case POINTER => getUnsignedByte(getBig)
      case t       => t
    }

  def getType(addr: Long): Int = {
    pos = addr
    getType
  }

  def getTimestamp: Instant = Instant.ofEpochMilli(getLong)

  def putTimestamp(t: Instant): Unit = putLong(t.toEpochMilli)

  def getDatetime: OffsetDateTime =
    OffsetDateTime.of(getInt, getByte, getByte, getByte, getByte, getByte, getInt, ZoneOffset.ofTotalSeconds(getSmall))

  def putDatetime(datetime: OffsetDateTime): Unit = {
    putInt(datetime.getYear)
    putByte(datetime.getMonthValue)
    putByte(datetime.getDayOfMonth)
    putByte(datetime.getHour)
    putByte(datetime.getMinute)
    putByte(datetime.getSecond)
    putInt(datetime.getNano)
    putSmall(datetime.getOffset.getTotalSeconds)
  }

  def getUUID = new UUID(getLong, getLong)

  def putUUID(id: UUID): Unit = {
    putLong(id.getMostSignificantBits)
    putLong(id.getLeastSignificantBits)
  }

  def getBoolean: Boolean =
    getByte match {
      case TRUE  => true
      case FALSE => false
      case _     => sys.error("invalid boolean value")
    }

  private def bool2int(a: Boolean) = if (a) TRUE else FALSE

  def putBoolean(a: Boolean): Unit = putByte(bool2int(a))

  object Type1 {
    def unapply(t: Int): Option[(Int, Int)] = {
      Some(t & 0xf0, t & 0x0f)
    }
  }

  object Type2 {
    def unapply(t: Int): Option[(Int, Int, Int)] = {
      Some(t & 0xf0, t & 0x04, t & 0x03)
    }
  }

  def getValue: Any = {
    val cur = pos
    val res =
      getType match {
        case NULL                => null
        case NSTRING             => ""
        case FALSE               => false
        case TRUE                => true
        case BYTE                => getByte
        case SHORT               => getShort
        case INT                 => getInt
        case LONG                => getLong
        case TIMESTAMP           => getTimestamp
        case DATETIME            => getDatetime
        case UUID                => getUUID
        case DOUBLE              => getDouble
        case Type1(SSTRING, len) => getString(len + 1)
        case Type2(STRING, encoding, width) =>
          val len =
            width match {
              case UBYTE_LENGTH  => getUnsignedByte
              case USHORT_LENGTH => getUnsignedShort
              case INT_LENGTH    => getInt
            }

          if (encoding == ENCODING_INCLUDED)
            getString(len, Charset.forName(getByteString.get))
          else
            getString(len)
        case EMPTY       => Map.empty
        case EMPTY_ARRAY => new Array[Any](0)
        case ARRAY_ELEMS => getArray
        case ARRAY_MEMS  => getArrayObject
        case NIL         => Nil
        case LIST_ELEMS  => getList
        case LIST_MEMS   => getListObject
        case BLOB        => getBlob
      }

    pos = cur + vwidth
    res
  }

  def putAlloc(t: Int): Unit = {
    putByte(POINTER)

    val io = allocPad()

    io.putByte(t)
    io
  }

  def putSimple(t: Int): Unit = {
    putByte(t)
    padCell
  }

  def putValue(v: Any): Unit = {
    v match {
      case null => putSimple(NULL)
      case ""   => putSimple(NSTRING)
      case b: Boolean =>
        putBoolean(b)
        padCell
      case a: Int if a.isValidByte =>
        putByte(BYTE)
        putByte(a)
        pad(cwidth - 1)
      case a: Int if a.isValidShort =>
        val (io, p) = need(2)

        io.putByte(SHORT)
        io.putShort(a)
        pad(p)
      case a: Int =>
        val (io, p) = need(4)

        io.putByte(INT)
        io.putInt(a)
        pad(p)
      case a: Long if a.isValidByte =>
        putByte(BYTE)
        putByte(a.asInstanceOf[Int])
        pad(cwidth - 1)
      case a: Long if a.isValidShort =>
        val (io, p) = need(2)

        io.putByte(SHORT)
        io.putShort(a.asInstanceOf[Int])
        pad(p)
      case a: Long if a.isValidInt =>
        val (io, p) = need(4)

        io.putByte(INT)
        io.putInt(a.asInstanceOf[Int])
        pad(p)
      case a: Long =>
        val (io, p) = need(8)

        io.putByte(LONG)
        io.putLong(a)
        pad(p)
      case a: Instant =>
        val (io, p) = need(8)

        io.putByte(TIMESTAMP)
        io.putTimestamp(a)
        pad(p)
      case a: OffsetDateTime =>
        val (io, p) = need(DATETIME_WIDTH)

        io.putByte(DATETIME)
        io.putDatetime(a)
        pad(p)
      case a: UUID =>
        val (io, p) = need(16)

        io.putByte(UUID)
        io.putUUID(a)
        pad(p)
      case a: Double =>
        val (io, p) = need(8)

        io.putByte(DOUBLE)
        io.putDouble(a)
        pad(p)
      case a: String =>
        val s = encode(a)
        val (io, p) = need(s.length match {
          case l if l <= cwidth => l
          case l if l < 256     => l + 1
          case l if l < 65536   => l + 2
          case l                => l + 4
        })

        if (s.length > cwidth) {
          s.length match {
            case l if l < 256 =>
              io.putByte(STRING | UBYTE_LENGTH)
              io.putByte(l)
            case l if l < 65536 =>
              io.putByte(STRING | USHORT_LENGTH)
              io.putShort(l)
            case l =>
              io.putByte(STRING | INT_LENGTH)
              io.putInt(l)
          }

          io.putBytes(s)
        } else {
          io.putByte(SSTRING | (s.length - 1))
          io.putBytes(s)
        }

        pad(p)
      case a: collection.Map[_, _] if a isEmpty => putSimple(EMPTY)
      case a: collection.Map[_, _] =>
        putAlloc(ARRAY_MEMS).putArrayObject(
          a.asInstanceOf[collection.Map[Any, Any]],
        ) // putAlloc( LIST_MEMS ).putListObject( a )
      case a: collection.IndexedSeq[_] if a isEmpty            => putSimple(EMPTY_ARRAY)
      case a: collection.IndexedSeq[_]                         => putAlloc(ARRAY_ELEMS).putArray(a)
      case a: collection.IterableOnce[_] if a.iterator.isEmpty => putSimple(NIL)
      case a: collection.IterableOnce[_]                       => putAlloc(LIST_ELEMS).putList(a)
      case a: Blob                                             => putAlloc(BLOB).putBlob(a)
      case a                                                   => sys.error("unknown type: " + a)
    }
  }

  def getValue(addr: Long): Any = {
    pos = addr
    getValue
  }

  def putValue(addr: Long, v: Any): Unit = {
    pos = addr
    putValue(v)
  }

  def getBlob = new IOBlob(this, pos + pwidth, getBig)

  def putBlob(a: Blob): Unit = {
    putBig(a.length)

    val s = a.stream
    val buf = new Array[Byte](1000)
    var len = 0

    while ({ len = s.read(buf); len != -1 })
      putBytes(buf, 0, len)

    s.close
  }

  def putArrayObject(m: collection.Map[Any, Any]): Unit = {
    putBig(m.size * 2)

    for ((k, v) <- m) {
      putValue(k)
      putValue(v)
    }
  }

  def getArrayObject: Map[Any, Any] = {
    val pairs =
      for (_ <- 1L to getBig / 2)
        yield {
          getValue -> getValue
        }

    Map(pairs: _*)
  }

  def getListObject: Map[Any, Any] = listElemsIterator grouped 2 map { case Seq((_, k), (_, v)) =>
    (getValue(k), getValue(v))
  } toMap

  def putListObject(m: collection.Map[_, _]): Unit = putList(m.iterator.flatMap(_.productIterator))

  def putListObject(addr: Long, m: collection.Map[_, _]): Unit = {
    pos = addr
    putListObject(m)
  }

  def getArray: IndexedSeq[Any] = arrayElemsIterator map getValue to ArraySeq

  def putArray(a: collection.IndexedSeq[Any]): Unit = {
    putBig(a.length)
    a foreach putValue
  }

  def getList: Seq[Any] = listElemsIterator map { case (_, e) => getValue(e) } toList

  def putList(s: collection.IterableOnce[Any]): Unit = {
    padBig // last chunk pointer
    padBig // chunks with freed elements list pointer

    val cur = pos

    padBig // length
    putListChunk(s, this, cur)
  }

  def putListChunk(s: collection.IterableOnce[Any], lengthio: IO, lengthptr: Long, contptr: Long = NUL): Unit = {
    putBig(contptr) // continuation pointer
    padBig // next chunk with freed elements pointer
    padBig // free pointer

    val lenptr = pos

    padBig // length of chunk in bytes

    val countptr = pos

    padBig // count of elements

    val elemsptr = pos
    var count = 0L

    for (e <- s.iterator) {
      putValue(e)
      count += 1
    }

    putBig(lenptr, pos - elemsptr)
    putBig(countptr, count)
    lengthio.addBig(lengthptr, count)
  }

  def byteInputStream(addr: Long, length: Long) = {
    require(size >= 0, "size must be non-negative")

    new InputStream {
      var cur = addr

      def read = {
        if (cur < addr + length) {
          val res = getByte(cur)

          cur += 1
          res
        } else -1
      }
    }
  }

  //
  // List operations
  //

  def removeListElement(list: Long, chunk: Long, elem: Long): Unit = {
    val (freeptr, lenptr) =
      getType(list) match {
        case NIL                    => sys.error("can't use 'removeListElement' on an empty list")
        case LIST_ELEMS | LIST_MEMS => (pos + pwidth, pos + 2 * pwidth)
        case t                      => sys.error(f"can only use 'removeListElement' for a list: $t%x, $pos%x")
      }
    val nextptr = getBig(chunk + 2 * pwidth)

    if nextptr == NUL then
      putBig(chunk + pwidth, getBig(freeptr))
      putBig(freeptr, chunk)

    putBig(chunk + 2 * pwidth, elem)
    addBig(lenptr, -1)
    addBig(chunk + 4 * pwidth, -1)
    remove(elem)
    putByte(elem, DELETED)
    putBig(nextptr)
  }

  //
  // Iterators
  //

  private def arrayElemsIterator: Iterator[Long] =
    new AbstractIterator[Long] {
      private var count = getBig
      private var cur = pos

      def hasNext: Boolean = count > 0

      def next: Long =
        if (hasNext) {
          val res = cur

          cur += vwidth
          count -= 1
          res
        } else throw new NoSuchElementException("next on empty arrayElemsIterator")
    }

  def arrayIterator(addr: Long): Iterator[Long] =
    getType(addr) match {
      case NIL         => Iterator.empty
      case ARRAY_ELEMS => arrayElemsIterator
      case _           => sys.error("can only use 'arrayIterator' for an array")
    }

  def arrayObjectIterator(addr: Long): Iterator[(Long, Long)] =
    getType(addr) match {
      case NIL        => Iterator.empty
      case ARRAY_MEMS => arrayElemsIterator grouped 2 map { case Seq(k, v) => (k, v) }
      case _          => sys.error("can only use 'arrayObjectIterator' for an array object")
    }

  private def listElemsIterator = {
    val header = pos
    val first = header + 3 * pwidth

    new AbstractIterator[(Long, Long)] {
      var cont: Long = _
      var chunksize: Long = _
      var chunkptr: Long = _
      var cur: Long = _
      var done = false

      chunk(first)
      nextused

      private def chunk(p: Long): Unit = {
        chunkptr = p
        cont = getBig(p)
        skipBig // next freed pointer
        skipBig // free pointer
        chunksize = getBig
        skipBig // skip count
        cur = pos
      }

      private def nextused: Unit = {
        if (chunksize == 0)
          if (cont == NUL) done = true
          else {
            chunk(cont)
            nextused
          }
        else {
          chunksize -= vwidth

          if (peekUnsignedByte(cur) == DELETED) {
            cur += vwidth
            nextused
          }
        }
      }

      def hasNext: Boolean = !done

      def next: (Long, Long) =
        if (done) throw new NoSuchElementException("next on empty listElemsIterator")
        else {
          val res = (chunkptr, cur)

          cur += vwidth
          nextused
          res
        }
    }
  }

  def listIterator(addr: Long): Iterator[(Long, Long)] =
    getType(addr) match {
      case NIL        => Iterator.empty
      case LIST_ELEMS => listElemsIterator
      case _          => sys.error("can only use 'listIterator' for a list")
    }

  def listObjectIterator(addr: Long): Iterator[((Long, Long), (Long, Long))] =
    getType(addr) match {
      case EMPTY     => Iterator.empty
      case LIST_MEMS => listElemsIterator grouped 2 map { case Seq(k, v) => (k, v) }
      case _         => sys.error("can only use 'listObjectIterator' for a list object")
    }

  //
  // utility methods
  //

  def remaining: Long = size - pos

  def atEnd = pos == size - 1

  def dump: Unit = {
    val cur = pos
    val width = 16

    pos = 0

    def printByte(b: Int) = print("%02x ".format(b & 0xff).toUpperCase)

    def printChar(c: Int) = print(if (' ' <= c && c <= '~') c.asInstanceOf[Char] else '.')

    for (line <- 0L until size by width) {
      printf(s"%10x  ", line)

      val mark = pos

      for (i <- line until ((line + width) min size)) {
        if (i % 16 == 8)
          print(' ')

        printByte(getByte)
      }

      val bytes = (pos - mark).asInstanceOf[Int]

      print(" " * ((width - bytes) * 3 + 1 + (if (bytes < 9) 1 else 0)))

      pos = mark

      for (_ <- line until ((line + width) min size))
        printChar(getByte)

      println
    }

    pos = cur
    println
  }

  def check: Unit = {
    val stack = new ArrayStack[String]
//		val reclaimed = new ArrayBuffer[(Long, Long)]	//todo: use in checking reclaimed storage

    def problem(msg: String, adjust: Int = 0): Unit = {
      println(f"${pos - adjust}%16x: $msg")

      for (item <- stack)
        println(item)

//			dump
      sys.error("check failed")
    }

    def push(item: String, adjust: Int = 0): Unit =
      stack push f"${pos - adjust}%16x: $item"

    def pop = stack.pop

    def checkif(c: Boolean, msg: String, adjust: Int = 0) =
      if (!c)
        problem(msg, adjust)

    def checkbytes(n: Int) = checkif(remaining >= n, f"${n - remaining}%x past end")

    def checkubyte = {
      checkbytes(1)
      getUnsignedByte
    }

    def checkushort = {
      checkbytes(2)
      getUnsignedShort
    }

    def checkint = {
      checkbytes(4)
      getInt
    }

    def checkbig = {
      checkbytes(pwidth)

      val p = getBig

      checkif(0 <= p && p < size - 1, f"pointer out of range: $p%x", pwidth)
      // todo: check if pointer points to reclaimed space
      p
    }

    def checkbytestring(s: String) = {
      val l = checkubyte

      checkif(l > 0, s"byte string size should be positive: $l")
      checkbytes(l)

      val chars = readByteChars(l)

      if (s ne null)
        checkif(chars == s, "incorrect byte string", l)

      (chars, l + 1)
    }

    def checkbyterange(from: Int, to: Int) = {
      val b = checkubyte

      checkif(from <= b && b <= to, s"byte out of range: $from to $to")
      b
    }

    def checkpos(p: Long, adjust: Int = 0): Unit = {
      checkif(0 < p && p < size, f"invalid file position: $p%x", adjust)
      pos = p
    }

    def checkvalue: Unit = {
      val cur = pos

      checkbytes(vwidth)
      // todo: check if we've wondered into reclaimed space

      checkubyte match {
        case POINTER =>
          checkpos(checkbig, pwidth)
          checkdata(checkubyte)
        case t => checkdata(t)
      }

      pos = cur + vwidth
    }

    def checkdata(t: Int): Unit = {
      // todo: check if allocation block is the correct size
      t match {
        case NULL | NSTRING | FALSE | TRUE | EMPTY | EMPTY_ARRAY | NIL | BYTE | SHORT | INT | LONG =>
        case BIGINT => sys.error("BIGINT")
        case DOUBLE => sys.error("DOUBLE")
        case Type1(SSTRING, l) =>
          push("small string", 1)
          checkif(0 <= l && l <= 0xf, s"small string length out of range: $l", 1)
          skipCell
          pop
        case Type2(STRING, encoding, width) =>
          push("string")
          val len =
            width match {
              case UBYTE_LENGTH  => checkubyte
              case USHORT_LENGTH => checkushort
              case INT_LENGTH    => checkint
            }

          if (encoding == ENCODING_INCLUDED) {
            val (cs, css) = checkbytestring(null)

            checkif(Charset.isSupported(cs), s"charset not supported: $cs", css)
          }

          checkbytes(len)
          skip(len)
          pop
        case ARRAY_ELEMS | ARRAY_MEMS =>
          push("array", 1)

          for (_ <- 1L to checkbig)
            checkvalue

          pop
        case LIST_ELEMS | LIST_MEMS =>
          push("list", 1)

//					val first = checkbig
          val lastptr = pos
          val last =
            checkbig match {
              case NUL => pos + 2 * pwidth
              case l   => l
            }
          /*val freed =*/
          checkbig // todo: use freed check free list

          val countptr = pos
          val count = checkbig

          var elemcount = 0

          def chunk: Unit = {
            val chunkheader = pos

            var chunkelemcount = 0

            push("list chunk")
            push("next chunk pointer")
            val cont = checkbig
            pop

            push("next freed pointer")
            /*val nfree =*/
            checkbig // todo: use nfree to check free list
            pop

            push("free pointer")
            /*val free =*/
            checkbig // todo: use free to check free list
            pop

            push("chunk length")
            val len = checkbig
            checkif(0 < len && len % vwidth == 0, "must be positive and a multiple of vwidth", pwidth)
            pop

            push("chunk count")
            val countptr = pos
            val count = checkbig
            checkif(
              0 <= count && count <= len / vwidth,
              "must be non-negative and less than or equal to length/vwidth",
              pwidth,
            ) // todo: count should never be 0 if empty chunks are removed immediately
            pop

            val start = pos

            while (pos - start < len)
              if (peekUnsignedByte == DELETED) {
                checkbytes(vwidth)
                skipValue
              } else {
                checkvalue
                elemcount += 1
                chunkelemcount += 1
              }

            pos = countptr
            checkif(chunkelemcount == count, "incorrect chunk count")

            if (cont > 0) {
              checkpos(cont)
              chunk
            } else {
              pos = lastptr
              checkif(chunkheader == last, "incorrect last chunk pointer")
            }

            pop
          }

//					if (first != NUL)
//						checkpos( first, pwidth )

          chunk
          pos = countptr
          checkif(elemcount == count, s"count is incorrect: count is $count but read $elemcount elements")
          pop
        case b => problem(f"unknown type byte: $b%02x", 1)
      }
    }

    def checkbucket(block: Long, bucket: Int): Unit = {
      if (block != NUL) {
        push(s"bucket $bucket", pwidth)
        checkpos(block - 1)
        checkif(checkubyte == bucket, "incorrect bucket index byte", 1)
        checkbucket(checkbig, bucket)
        pop
      }
    }

    pos = 0
    push("file header")
    push("file type")
    checkbytestring(IO.filetype)
    pop

    push("format version")
    checkbytestring(null)
    pop

    push("charset")
    checkbytestring(null)
    pop

    push("pointer width")
    checkif(checkbyterange(1, 8) == pwidth, "changed", 1)
    pop

    push("cell width")
    checkif(checkbyterange(1, 16) == cwidth, "changed", 1)
    pop

    push("uuid")
    checkif(checkbyterange(FALSE, TRUE) == bool2int(uuidOption), "changed", 1)
    pop

    push("buckets")
    checkif(bucketLen == buckets.length, "lengths don't match")

    for (i <- 0 until bucketLen) {
      checkif(checkbig == buckets(i), f"pointer mismatch - bucket array has ${buckets(i)}%x", pwidth)

      val bucket = pos

      checkbucket(buckets(i), i)
      pos = bucket
    }

    pop
    pop

    push("root")
    checkvalue
    pop
  }

  def skip(len: Long) = pos += len

  def skipByte = pos += 1

  def skipByte(addr: Long): Unit = {
    pos = addr
    skipByte
  }

  def skipType(addr: Long): Unit = {
    if (getUnsignedByte(addr) == POINTER)
      skipByte(getBig)
  }

  def skipBig = skip(pwidth)

  def skipInt = skip(4)

  def skipLong = skip(8)

  def skipDouble = skip(8)

  def skipValue = skip(vwidth)

  def skipCell = skip(cwidth)

  def pad(n: Long) =
    for (_ <- 1L to n)
      putByte(0)

  def padBig = pad(pwidth)

  def padCell = pad(cwidth)

  def encode(s: String) = s.getBytes(charset)

  def inert(action: => Unit): Unit = {
    val cur = pos

    action
    pos = cur
  }

  def need(width: Int) =
    if (width > cwidth) {
      putByte(POINTER)
      (alloc(), cwidth)
    } else (this, cwidth - width)

  def todo = sys.error("not implemented")

  //
  // allocation
  //

  private[storage] val allocs = new ListBuffer[AllocIO]
  private[storage] var appendbase: Long = _

  def remove(addr: Long): Unit = {
    if (getUnsignedByte(addr) == POINTER) {
      val p = getBig

      getUnsignedByte(p) match {
        case LIST_ELEMS =>
          for ((_, e) <- listIterator(p))
            remove(e)
        case LIST_MEMS =>
          for (((_, k), (_, v)) <- listObjectIterator(p)) {
            remove(k)
            remove(v)
          }
        case ARRAY_ELEMS =>
          arrayIterator(p) foreach remove
        case ARRAY_MEMS =>
          for ((k, v) <- arrayObjectIterator(p)) {
            remove(k)
            remove(v)
          }
        case _ =>
      }

      dealloc(p)
    }
  }

  def bucketPtr(bucketIndex: Int) = bucketIndex * pwidth + bucketsPtr

  def dealloc(p: Long): Unit = {
    val ind = getByte(p - 1)

    putBig(p, buckets(ind))
    buckets(ind) = p
    putBig(bucketPtr(ind), p)
  }

  def alloc(): AllocIO = {
    val res = new AllocIO(this)

    allocs += res
    res.backpatch(this, pos)
    res
  }

  def allocPad(): AllocIO = {
    val res = alloc()

    padCell
    res
  }

  private[storage] def placeAllocs(io: IO): Unit = {
    for (a <- allocs) {
      io.buckets(a.bucket) match {
        case NUL =>
          a.base = io.appendbase + 1
          io.appendbase += a.allocSize
        case p =>
          val ptr = io.getBig(p)

          io.buckets(a.bucket) = ptr
          io.putBig(bucketPtr(a.bucket), ptr)
          a.base = p
      }

      a.placeAllocs(io)
    }
  }

  private[storage] def writeAllocBackpatches(): Unit = {
    for (a <- allocs) {
      a.writeBackpatches
      a.writeAllocBackpatches()
    }
  }

  private[storage] def writeAllocs(dest: IO): Unit = {
    for (a <- allocs) {
      dest.pos = a.base - 1
      dest.putByte(a.bucket)
      dest.writeBuffer(a)
      dest.pad(a.allocSize - a.size - 1)
      a.writeAllocs(dest)
    }
  }

  def finish: Unit = {
    if (allocs.nonEmpty)
    {
      append
      appendbase = pos
      placeAllocs(this)
      writeAllocBackpatches()
      writeAllocs(this)
      allocs.clear
    }

    force()
  }
}
