package com.redis

import com.redis.serialization._


case class GeoRadiusMember(member: Option[String],
                           hash: Option[Long] = None,
                           dist: Option[String] = None,
                           coords: Option[(String, String)] = None)
trait GeoReceive { self: Reply =>
  import Commands._
  import Parse.{Implicits => Parsers}
  import Parse.Implicits._


//  case class GeoRadiusMember(member: Option[String], complexResponse: Option[ComplexGeoRadiusMember])

  type FoldReply = PartialFunction[(Char, Array[Byte], Option[GeoRadiusMember]), Option[GeoRadiusMember]]


  def errFoldReply[A]: FoldReply = {
    case (ERR, s, _) => throw new Exception(Parsers.parseString(s))
    case x => throw new Exception("Protocol error: Got " + x + " as initial reply byte")
  }

  def foldReceive[A](pf: FoldReply, a: Option[GeoRadiusMember]): Option[GeoRadiusMember] = readLine match {
    case null =>
      throw new RedisConnectionException("Connection dropped ..")
    case line =>
      println("Just Received " + line(0).toChar)
      (pf orElse errFoldReply) apply ((line(0).toChar,line.slice(1,line.length),a))
  }

  val phaseThree: PartialFunction[(Char, Array[Byte], Option[GeoRadiusMember]), Option[GeoRadiusMember]] = {
    case (BULK, s, a) =>
      val retrieved = Parsers.parseInt(s) match {
        case -1 => None
        case l =>
          val str = readCounted(l)
          val ignore = readLine // trailing newline
          Some(str)
        case _ => None
      }
      retrieved.map{ ret =>
        a.fold(GeoRadiusMember(Some(ret))){ some =>
          some.member.fold(GeoRadiusMember(Some(ret))){_ => some.copy(dist = Some(ret))}
        }
      }
    case (INT, s, opt) => opt.map( a => a.copy(hash = Some(Parsers.parseLong(s))))
  }

  def phaseTwo[A]: Reply[Option[GeoRadiusMember]] = {
    case (BULK, s) =>
      Parsers.parseInt(s) match {
        case -1 => None
        case l =>
          val str = readCounted(l)
          val ignore = readLine // trailing newline
          Some(GeoRadiusMember(Some(str)))
        case _ => None
      }
    case (MULTI, str) =>
      Parsers.parseInt(str) match {
        case -1 => None
        case n =>
          val out: Option[GeoRadiusMember] = List.range(0, n).foldLeft[Option[GeoRadiusMember]](None){ (in, n) =>
            foldReceive(phaseThree, in)
          }
          out
      }
  }

  val geoRadiusMemberReply: Reply[Option[List[Option[GeoRadiusMember]]]] = {
    case (MULTI, str) =>
      Parsers.parseInt(str) match {
        case -1 => None
        case n => Some(List.fill(n)(receive(phaseTwo)))
      }
  }

}
/**
  * Created by alexis on 05/09/16.
  */
trait GeoOperations { self: Redis =>

  private def flattenProduct3(in: Iterable[Product3[Any, Any, Any]]): List[Any] =
    in.iterator.flatMap(x => Iterator(x._1, x._2, x._3)).toList

  def geoadd(key: Any, members: Iterable[Product3[Any, Any, Any]]): Option[Int] = {
    send("GEOADD", key :: flattenProduct3(members))(asInt)
  }

  def geopos[A](key: Any, members: Iterable[Any])(implicit format: Format, parse: Parse[A]): Option[List[Option[List[Option[A]]]]] = {
    send("GEOPOS", key :: members.toList)(receive(multiBulkNested).map(_.map(_.map(_.map(_.map(parse))))))
  }

  def geohash[A](key: Any, members: Iterable[Any])(implicit format: Format, parse: Parse[A]): Option[List[A]]= {
    send("GEOHASH", key :: members.toList)(asList.map(_.flatten))
  }

  def geodist(key: Any, m1: Any, m2: Any, unit: Option[Any]): Option[String] = {
    send("GEODIST", List(key, m1, m2) ++ unit.toList)(asBulk[String])
  }

  def georadius[A](key: Any,
                longitude: Any,
                latitude: Any,
                radius: Any,
                unit: Any,
                withCoord: Boolean,
                withDist: Boolean,
                withHash: Boolean,
                count: Option[Any],
                order: Option[Any],
                store: Option[Any],
                storeDist: Option[Any])(implicit format: Format, parse: Parse[A]): Option[List[Option[GeoRadiusMember]]] ={
    send("GEORADIUS", List(key, longitude, latitude, radius, unit) ++ List("WITHDIST", "WITHHASH"))(receive(geoRadiusMemberReply))
  }

}
