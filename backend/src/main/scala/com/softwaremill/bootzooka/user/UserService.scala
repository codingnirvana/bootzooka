package com.softwaremill.bootzooka.user

import java.util.UUID

import com.softwaremill.bootzooka.common.{Clock, Utils}
import com.softwaremill.bootzooka.email.{EmailTemplatingEngine, EmailService}

import scala.concurrent.{ExecutionContext, Future}

class UserService(
    userDao: UserDao,
    emailService: EmailService,
    emailTemplatingEngine: EmailTemplatingEngine
)(implicit ec: ExecutionContext, clock: Clock) {

  def findById(userId: userDao.UserId) = {
    userDao.findById(userId).map(toUserJson)
  }

  def registerNewUser(login: String, email: String, password: String): Future[UserRegisterResult] = {
    if (!RegisterDataValidator.isDataValid(login, email, password)) {
      Future.successful(UserRegisterResult.InvalidData)
    }
    else {
      checkUserExistenceFor(login, email).flatMap {
        case Left(msg) => Future.successful(UserRegisterResult.UserExists(msg))
        case Right(_) =>
          val salt = Utils.randomString(128)
          val now = clock.nowUtc
          val userAddResult = userDao.add(User.withRandomUUID(login, email.toLowerCase, password, salt, now))
          userAddResult.onSuccess {
            case _ =>
              val confirmationEmail = emailTemplatingEngine.registrationConfirmation(login)
              emailService.scheduleEmail(email, confirmationEmail)
          }
          userAddResult.map(_ => UserRegisterResult.Success)
      }
    }
  }

  def authenticate(login: String, nonEncryptedPassword: String): Future[Option[UserJson]] = {
    userDao.findByLoginOrEmail(login).map(userOpt =>
      toUserJson(userOpt.filter(u => User.passwordsMatch(nonEncryptedPassword, u))))
  }

  private def toUserJson(userOpt: Option[User]) = userOpt.map(UserJson(_))

  def checkUserExistenceFor(login: String, email: String): Future[Either[String, Unit]] = {
    val existingLoginFuture = userDao.findByLowerCasedLogin(login)
    val existingEmailFuture = userDao.findByEmail(email).map(toUserJson)

    for {
      existingLoginOpt <- existingLoginFuture
      existingEmailOpt <- existingEmailFuture
    } yield {
      existingLoginOpt.map(_ => Left("Login already in use!")).orElse(
        existingEmailOpt.map(_ => Left("E-mail already in use!"))
      ).getOrElse(Right((): Unit))
    }
  }

  def changeLogin(userId: UUID, newLogin: String): Future[Either[String, Unit]] = {
    userDao.findByLowerCasedLogin(newLogin).flatMap {
      case Some(_) => Future.successful(Left("Login is already taken"))
      case None => userDao.changeLogin(userId, newLogin).map(Right(_))
    }
  }

  def changeEmail(userId: UUID, newEmail: String): Future[Either[String, Unit]] = {
    userDao.findByEmail(newEmail).flatMap {
      case Some(_) => Future.successful(Left("E-mail used by another user"))
      case None => userDao.changeEmail(userId, newEmail).map(Right(_))
    }
  }

  def changePassword(userId: UUID, currentPassword: String, newPassword: String): Future[Either[String, Unit]] = {
    userDao.findById(userId).flatMap {
      case Some(u) => if (User.passwordsMatch(currentPassword, u)) {
        userDao.changePassword(u.id, User.encryptPassword(newPassword, u.salt)).map(Right(_))
      }
      else {
        Future {
          Left("Current password is invalid")
        }
      }
      case None => Future {
        Left("User not found hence cannot change password")
      }
    }
  }
}

sealed trait UserRegisterResult
object UserRegisterResult {
  case object InvalidData extends UserRegisterResult
  case class UserExists(msg: String) extends UserRegisterResult
  case object Success extends UserRegisterResult
}
