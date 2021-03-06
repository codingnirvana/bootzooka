package com.softwaremill.bootzooka.user

import java.util.UUID

import com.softwaremill.session.{MultiValueSessionSerializer, SessionSerializer}

import scala.util.Try

case class Session(userId: UUID)

object Session {
  implicit val serializer: SessionSerializer[Session, String] = new MultiValueSessionSerializer[Session](
    (t: Session) => Map("id" -> t.userId.toString),
    m => Try { Session(UUID.fromString(m("id"))) }
  )
}