/**
 * Copyright (c) 2011-2016 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.debiki.core

import java.net.InetAddress
import java.{net => jn, util => ju}
import org.scalactic.{Or, Every, ErrorMessage}
import scala.collection.mutable
import EmailNotfPrefs.EmailNotfPrefs
import Prelude._
import User._



abstract class People {

  def theUser(id: UserId): User

}


sealed abstract class Presence(val IntVal: Int) { def toInt = IntVal }
object Presence {
  case object Active extends Presence(1)
  case object Away extends Presence(2)
}


/** An invite to the user with the specified emailAddress to join the site.
  * S/he gets an email and clicks a link to join.
  */
case class Invite(
  emailAddress: String,
  secretKey: String,
  createdById: UserId,
  createdAt: ju.Date,
  acceptedAt: Option[ju.Date],
  userId: Option[UserId],
  deletedAt: Option[ju.Date],
  deletedById: Option[UserId],
  invalidatedAt: Option[ju.Date]) {

  require(secretKey.length > 15, "Unsafe key? [DwE7KFPE2]")
  require(emailAddress contains "@", "DwE64KSU8")
  require(emailAddress.split("@").head.nonEmpty, "DwE09FKI2")
  require(acceptedAt.isEmpty == userId.isEmpty, "DwE8KE94")
  require(deletedAt.isEmpty == deletedById.isEmpty, "DwE4KSP3")
  require(invalidatedAt.toInt + deletedAt.toInt + acceptedAt.toInt <= 1, "DwE5WKJ2")
  require(deletedAt.isEmpty || deletedAt.get.getTime >= createdAt.getTime, "DwE6PK2")
  require(invalidatedAt.isEmpty || invalidatedAt.get.getTime >= createdAt.getTime, "DwE8UY0")

  def canBeOrHasBeenAccepted = invalidatedAt.isEmpty && deletedAt.isEmpty

  /** Suggests a username based on the email address, namely the text before the '@'.
    * Padded with "_" if too short.
    */
  private def deriveUsername: String = {
    var username = emailAddress.split("@").headOption.getOrDie(
      "DwE500IIEA5", "Invalid invite email address")
    while (username.length < MinUsernameLength) {
      username += "_"
    }
    username
  }

  def makeUser(userId: UserId, currentTime: ju.Date) = CompleteUser(
    id = userId,
    fullName = None,
    username = deriveUsername,
    createdAt = currentTime,
    isApproved = None,
    approvedAt = None,
    approvedById = None,
    emailAddress = emailAddress,
    emailNotfPrefs = EmailNotfPrefs.Receive,
    emailVerifiedAt = Some(currentTime))
}


object Invite {
  def apply(emailAddress: String, secretKey: String,
        createdById: UserId, createdAt: ju.Date): Invite = Invite(
    emailAddress = emailAddress,
    secretKey = secretKey,
    createdById = createdById,
    createdAt = createdAt,
    userId = None,
    acceptedAt = None,
    deletedAt = None,
    deletedById = None,
    invalidatedAt = None)
}


// Rename to NewMemberData?
sealed abstract class NewUserData {
  def name: Option[String]
  def username: String
  def email: String
  def emailVerifiedAt: Option[ju.Date]
  def isAdmin: Boolean
  def isOwner: Boolean

  def makeUser(userId: UserId, createdAt: ju.Date) = CompleteUser(
    id = userId,
    fullName = name,
    username = username,
    createdAt = createdAt,
    isApproved = None,
    approvedAt = None,
    approvedById = None,
    emailAddress = email,
    emailNotfPrefs = EmailNotfPrefs.Receive,
    emailVerifiedAt = emailVerifiedAt,
    isAdmin = isAdmin,
    isOwner = isOwner)

  def makeIdentity(userId: UserId, identityId: IdentityId): Identity

  Validation.checkName(name)
  Validation.checkUsername(username)
  Validation.checkEmail(email)

}



case class NewPasswordUserData(
  name: Option[String],
  username: String,
  email: String,
  password: String,
  isAdmin: Boolean,
  isOwner: Boolean,
  isModerator: Boolean = false) {

  val passwordHash: String =
    DbDao.saltAndHashPassword(password)

  def makeUser(userId: UserId, createdAt: ju.Date) = CompleteUser(
    id = userId,
    fullName = name,
    username = username,
    createdAt = createdAt,
    isApproved = None,
    approvedAt = None,
    approvedById = None,
    emailAddress = email,
    emailNotfPrefs = EmailNotfPrefs.Receive,
    emailVerifiedAt = None,
    passwordHash = Some(passwordHash),
    isOwner = isOwner,
    isAdmin = isAdmin,
    isModerator = isModerator)

  Validation.checkName(name)
  Validation.checkUsername(username)
  Validation.checkEmail(email)
  Validation.checkPassword(password)
}


object NewPasswordUserData {
  def create(name: Option[String], username: String, email: String, password: String,
        isAdmin: Boolean, isOwner: Boolean, isModerator: Boolean = false)
        : NewPasswordUserData Or ErrorMessage = {
    for {
      okName <- Validation.checkName(name)
      okUsername <- Validation.checkUsername(username)
      okEmail <- Validation.checkEmail(email)
      okPassword <- Validation.checkPassword(password)
    }
    yield {
      NewPasswordUserData(name = okName, username = okUsername, email = okEmail,
        password = okPassword, isAdmin = isAdmin, isOwner = isOwner, isModerator = isModerator)
    }
  }
}



case class NewOauthUserData(
  name: Option[String],
  username: String,
  email: String,
  emailVerifiedAt: Option[ju.Date],
  identityData: OpenAuthDetails,
  isAdmin: Boolean,
  isOwner: Boolean) extends NewUserData {

  def makeIdentity(userId: UserId, identityId: IdentityId): Identity =
    OpenAuthIdentity(id = identityId, userId = userId, openAuthDetails = identityData)
}


object NewOauthUserData {
  def create(name: Option[String], email: String, emailVerifiedAt: Option[ju.Date], username: String,
        identityData: OpenAuthDetails, isAdmin: Boolean, isOwner: Boolean)
        : NewOauthUserData Or ErrorMessage = {
    for {
      okName <- Validation.checkName(name)
      okUsername <- Validation.checkUsername(username)
      okEmail <- Validation.checkEmail(email)
    }
    yield {
      NewOauthUserData(name = okName, username = okUsername, email = okEmail,
        emailVerifiedAt = emailVerifiedAt, identityData = identityData,
        isAdmin = isAdmin, isOwner = isOwner)
    }
  }
}



case class NameAndUsername(fullName: String, username: String)



case object User {

  /** The only other member is the System user. But it's a computer. */
  val LowestHumanMemberId = 1

  /** Used when things are inserted or updated automatically in the database. */
  val SystemUserId = -1
  val SystemUserUsername = "system"
  val SystemUserFullName = "System"

  // Perhaps in the future:
  // /** A user that has logged in and can post comments, but is anonymous. */
  // val AnonymousUserId = -2

  /** A user that did something, e.g. voted on a comment, but was not logged in. */
  val UnknownUserId = -3
  val UnknownUserName = "Unknown"
  val UnknownUserGuestCookie = "UU"

  /** Guests with custom name and email, but not guests with magic ids like the Unknown user. */
  val MaxCustomGuestId = -10

  val MaxGuestId = -2
  //assert(MaxGuestId == AnonymousUserId)
  assert(UnknownUserId.toInt <= MaxGuestId)

  /** Ids 1 .. 99 are reserved in case in the future I want to combine users and groups,
    * and then there'll be a few groups with hardcoded ids in the range 1..99.
    */
  val LowestAuthenticatedUserId = 100

  val LowestMemberId = SystemUserId // -1
  val LowestNonGuestId = -1 // later: rename to LowestMemberId
  assert(LowestNonGuestId == SystemUserId)
  assert(LowestNonGuestId == MaxGuestId + 1)

  def isGuestId(userId: UserId) =
    userId <= MaxGuestId

  def isRoleId(userId: UserId) =
    !isGuestId(userId)

  def isMember(userId: UserId) = userId >= LowestMemberId
  def isHumanMember(userId: UserId) = userId >= LowestHumanMemberId

  def isOkayUserId(id: UserId) =
    id >= LowestAuthenticatedUserId ||
      id == SystemUserId ||
      id == UnknownUserId ||
      id <= MaxCustomGuestId


  val MinUsernameLength = 3


  /**
   * Checks for weird ASCII chars in an user name.
   *
   * Cannot be used with names from identity providers, e.g. OpenID
   * or Twitter: the providers do their own user name sanity checks,
   * and we should handle anything they accept?
   */
  def nameIsWeird(name: String): Boolean = {
    // Could check for weird Unicode whitespace too, but that will
    // probably be implicitly solved, when handling spam? ASCII,
    // however, could mess up the internals of something, because
    // sometimes the system assigns magic meanings to ASCII chars
    // that only an attacker would use?
    for (c <- name if c < 0x80) {
      if (c < ' ') return true  // control chars
      if (c < '0' && !(" '-." contains c)) return true  // punctuation
      if (c > '9' && c < 'A') return true  // more punctuation
      if (c > 'Z' && c < 'a') return true  // even more punctuation
      if (c > 'z' && c <= 127) return true  // punctuation
    }
    false
  }


  /**
   * Checks for weird ASCII chars in an email,
   * and that it matches """.+@.+\..+""".
   */
  def emailIsWeird(email: String): Boolean = {
    // Differences from nameIsOk(): allow "@_", disallows "'".
    for (c <- email if c < 0x80) {
      if (c <= ' ') return true  // forbid control chars and space
      if (c < '0' && !(" -." contains c)) return true  // punctuation
      if (c > '9' && c < '@') return true  // email, so '@' ok
      if (c > 'Z' && c < 'a' && !"_".contains(c)) return true  // punctuation
      if (c > 'z' && c <= 127) return true  // punctuation
    }
    if (isEmailLocalPartHidden(email)) {
      return true
    }
    if (email matches """.+@.+\..+""") return false
    true
  }


  def isOkayGuestCookie(anyValue: Option[String]) = anyValue match {
    case None => false
    case Some(value) => value.nonEmpty && value.trim == value
  }


  def isSuspendedAt(now: ju.Date, suspendedTill: Option[ju.Date]): Boolean =
    suspendedTill.map(now.getTime <= _.getTime) == Some(true)

}


// Try to remove all fields unique for only Member and only Guest.
sealed trait User {
  def id: UserId
  def email: String  // COULD rename to emailAddr
  def emailNotfPrefs: EmailNotfPrefs
  def emailVerifiedAt: Option[ju.Date]
  def passwordHash: Option[String]
  def country: String
  def website: String
  def tinyAvatar: Option[UploadRef]
  def smallAvatar: Option[UploadRef]
  def isApproved: Option[Boolean]
  def suspendedTill: Option[ju.Date]
  def isAdmin: Boolean
  def isOwner: Boolean
  def isModerator: Boolean

  def isAuthenticated = isRoleId(id)
  def isApprovedOrStaff = isApproved.contains(true) || isStaff
  def isSystemUser = id == SystemUserId
  def isStaff = isAdmin || isModerator || isSystemUser
  def isHuman = id >= LowestHumanMemberId

  def isGuest = User.isGuestId(id)
  def anyRoleId: Option[RoleId] = if (isRoleId(id)) Some(id) else None

  def isSuspendedAt(when: ju.Date) = User.isSuspendedAt(when, suspendedTill = suspendedTill)

  def anyName: Option[String] = None
  def anyUsername: Option[String] = None
  def theUsername: String
  def usernameOrGuestName: String
}


case class Member(
  id: UserId,
  fullName: Option[String],
  theUsername: String,
  email: String,  // COULD rename to emailAddr
  emailNotfPrefs: EmailNotfPrefs,
  emailVerifiedAt: Option[ju.Date] = None,
  passwordHash: Option[String] = None,
  country: String = "",
  website: String = "",
  tinyAvatar: Option[UploadRef] = None,
  smallAvatar: Option[UploadRef] = None,
  isApproved: Option[Boolean],
  suspendedTill: Option[ju.Date],
  trustLevel: TrustLevel = TrustLevel.New,
  lockedTrustLevel: Option[TrustLevel] = None,
  threatLevel: ThreatLevel = ThreatLevel.HopefullySafe,
  lockedThreatLevel: Option[ThreatLevel] = None,
  isAdmin: Boolean = false,
  isOwner: Boolean = false,
  isModerator: Boolean = false) extends User {

  override def anyName = fullName
  override def anyUsername = username
  def username: Option[String] = Some(theUsername)
  def usernameOrGuestName = theUsername

  def usernameParensFullName: String = fullName match {
    case Some(name) => s"$theUsername ($name)"
    case None => theUsername
  }

  def effectiveTrustLevel = lockedTrustLevel getOrElse trustLevel
  def effectiveThreatLevel = lockedThreatLevel getOrElse threatLevel

  require(!fullName.map(_.trim).contains(""), "DwE4GUK28")
  require(User.isOkayUserId(id), "DwE02k12R5")
  require(theUsername.length >= 2, "EsE7YKW3")
  require(!isEmailLocalPartHidden(email), "DwE6kJ23")
  require(tinyAvatar.isDefined == smallAvatar.isDefined, "EdE5YPU2")
}


case class Guest(
  id: UserId,
  guestName: String,
  guestCookie: Option[String],
  email: String,  // COULD rename to emailAddr
  emailNotfPrefs: EmailNotfPrefs,
  country: String = "",
  lockedThreatLevel: Option[ThreatLevel] = None) extends User {

  def theUsername = die("EsE7YKWP4")
  def username = None
  def emailVerifiedAt: Option[ju.Date] = None
  def passwordHash: Option[String] = None
  def tinyAvatar: Option[UploadRef] = None
  def smallAvatar: Option[UploadRef] = None
  def website: String = ""
  def isApproved: Option[Boolean] = None
  def isAdmin: Boolean = false
  def isOwner: Boolean = false
  def isModerator: Boolean = false
  def suspendedTill: Option[ju.Date] = None

  override def anyName = Some(guestName)
  def usernameOrGuestName = guestName

  require(id <= MaxCustomGuestId, "DwE4GYUK21")
  require(guestName == guestName.trim, "EsE5YGUK3")
  require(guestName.nonEmpty, "DwE4KEPF8")
  require(User.isOkayGuestCookie(guestCookie), "DwE5QF7")
  require(!isEmailLocalPartHidden(email), "DwE6kJ23")
}


// Rename to MemberInclDetails?
case class CompleteUser(
  id: UserId,
  fullName: Option[String],
  username: String,
  createdAt: ju.Date,
  isApproved: Option[Boolean],
  approvedAt: Option[ju.Date],
  approvedById: Option[UserId],
  emailAddress: String,
  emailNotfPrefs: EmailNotfPrefs,
  emailVerifiedAt: Option[ju.Date] = None,
  emailForEveryNewPost: Boolean = false,
  passwordHash: Option[String] = None,
  country: String = "",
  website: String = "",
  tinyAvatar: Option[UploadRef] = None,
  smallAvatar: Option[UploadRef] = None,
  mediumAvatar: Option[UploadRef] = None,
  isOwner: Boolean = false,
  isAdmin: Boolean = false,
  isModerator: Boolean = false,
  suspendedAt: Option[ju.Date] = None,
  suspendedTill: Option[ju.Date] = None,
  suspendedById: Option[UserId] = None,
  suspendedReason: Option[String] = None,
  trustLevel: TrustLevel = TrustLevel.New,
  lockedTrustLevel: Option[TrustLevel] = None,
  threatLevel: ThreatLevel = ThreatLevel.HopefullySafe,
  lockedThreatLevel: Option[ThreatLevel] = None) {

  require(User.isOkayUserId(id), "DwE077KF2")
  require(username.length >= 2, "DwE6KYU9")
  require(approvedAt.isDefined == approvedById.isDefined, "DwE0KEI4")
  require(approvedById.map(_ >= LowestNonGuestId) != Some(false), "DwE55UKH4")
  require(isApproved.isEmpty || (approvedById.isDefined && approvedAt.isDefined), "DwE4DKQ1")
  require(suspendedAt.isDefined == suspendedById.isDefined, "DwE64kfe2")
  require(suspendedTill.isEmpty || suspendedAt.isDefined, "DwEJKP75")
  require(suspendedReason.isDefined == suspendedAt.isDefined, "DwE5JK26")
  require(suspendedReason.map(_.trim.length) != Some(0), "DwE2KFER0")
  require(suspendedReason.map(r => r.trim.length < r.length) != Some(true), "DwE4KPF8")
  require(suspendedById.map(_ >= LowestNonGuestId) != Some(false), "DwE7K2WF5")
  require(!isGuest, "DwE0GUEST223")
  require(!isEmailLocalPartHidden(emailAddress), "DwE2WFE1")
  require(tinyAvatar.isDefined == smallAvatar.isDefined &&
    smallAvatar.isDefined == mediumAvatar.isDefined, "EdE8UMW2")

  def isStaff = isAdmin || isModerator
  def isApprovedOrStaff = approvedAt == Some(true) || isStaff

  def isGuest = User.isGuestId(id)
  def anyRoleId: Option[RoleId] = if (isRoleId(id)) Some(id) else None

  def isSuspendedAt(when: ju.Date) = User.isSuspendedAt(when, suspendedTill = suspendedTill)

  def preferences = UserPreferences(
    userId = id,
    fullName = fullName,
    username = username,
    emailAddress = emailAddress,
    url = website,
    emailForEveryNewPost = emailForEveryNewPost)

  def copyWithNewPreferences(preferences: UserPreferences) = {
    val newEmailAddress =
      if (isEmailLocalPartHidden(preferences.emailAddress)) this.emailAddress
      else preferences.emailAddress
    copy(
      fullName = preferences.fullName,
      username = preferences.username,
      emailAddress = newEmailAddress,
      website = preferences.url,
      emailForEveryNewPost = preferences.emailForEveryNewPost)
  }

  def briefUser = Member(
    id = id,
    fullName = fullName,
    theUsername = username,
    email = emailAddress,
    emailNotfPrefs = emailNotfPrefs,
    emailVerifiedAt = emailVerifiedAt,
    passwordHash = passwordHash,
    country = country,
    website = website,
    isApproved = isApproved,
    suspendedTill = suspendedTill,
    trustLevel = trustLevel,
    lockedTrustLevel = lockedTrustLevel,
    threatLevel = threatLevel,
    lockedThreatLevel = lockedThreatLevel,
    isAdmin = isAdmin,
    isOwner = isOwner)
}



/**
 * Used when searching for users.
 */
case class UserQuery()


object EmailNotfPrefs extends Enumeration {
  type EmailNotfPrefs = Value
  val Receive, DontReceive, ForbiddenForever, Unspecified = Value
}


case class RolePageSettings(
  notfLevel: PageNotfLevel)

object RolePageSettings {
  val Default = RolePageSettings(PageNotfLevel.Regular)
}


sealed abstract class LoginAttempt {
  def ip: String
  def date: ju.Date
}


case class GuestLoginAttempt(
  ip: String,
  date: ju.Date,
  name: String,
  email: String = "",
  guestCookie: String) {

  require(ip == ip.trim, "DwE4KWF0")
  require(name == name.trim && name.trim.nonEmpty, "DwE6FKW3")
  require(email == email.trim, "DwE83WK2")
  require(guestCookie == guestCookie.trim && guestCookie.nonEmpty && guestCookie != "-", "DwE0FYF8")
}

case class GuestLoginResult(guest: Guest, isNewUser: Boolean)


case class PasswordLoginAttempt(
  ip: String,
  date: ju.Date,
  email: String,
  password: String) extends LoginAttempt {
}


case class EmailLoginAttempt(
  ip: String,
  date: ju.Date,
  emailId: String) extends LoginAttempt {
}


case class OpenIdLoginAttempt(
  ip: String,
  date: ju.Date,
  openIdDetails: OpenIdDetails) extends LoginAttempt {
}


case class OpenAuthLoginAttempt(
  ip: String,
  date: ju.Date,
  openAuthDetails: OpenAuthDetails) extends LoginAttempt {

  def profileProviderAndKey = openAuthDetails.providerIdAndKey
}


/** A user might have many identities, e.g. an OpenAuth Google identity and
  * a Twitter identity.
  */
sealed abstract class Identity {

  def id: IdentityId
  def userId: UserId

  //checkId(id, "DwE02krc3g")  TODO check how?
  require(isOkayUserId(userId), "DwE864rsk215")
}


/**
 * By specifying an id of an email that has been sent to you,
 * you can login. This login type is insecure (email transmission is
 * not secure) and only used for unsubscriptions.
 * @param id The email id. Should refer to an email that has already
 *  been saved in the database.
 * @param userId The user that received the email. Not known before
 *  login (is "?").
 * @param emailSent Not known before login (is `None`)
 */
case class IdentityEmailId(
  id: IdentityId,
  userId: UserId,
  emailSent: Option[Email] = None
) extends Identity {
  // Either only email id known, or all info known.
  // require((userId startsWith "?") == emailSent.isEmpty)    TODO what?
}


case class IdentityOpenId(
  id: IdentityId,
  override val userId: UserId,
  openIdDetails: OpenIdDetails) extends Identity {

  def displayName = openIdDetails.firstName
}


case class OpenIdDetails(
  oidEndpoint: String,
  oidVersion: String,
  oidRealm: String,  // perhaps need not load from db?
  // The OpenID depends on the realm, for Gmail. So for tenants
  // with different realms (e.g. realms *.debiki.net and another-domain.com)
  // the same user will be found in two different UserOpenID instances.
  // However their Gmail addresses will be identical, so for Gmail,
  // checking email could be helpful. But must ensure the OpenID provider
  // is Gmail! otherwise an evil provider could provide false email addresses.
  oidClaimedId: String,
  oidOpLocalId: String,
  firstName: String,
  email: Option[String],
  country: String) {

}


case class OpenAuthIdentity(
  id: IdentityId,
  override val userId: UserId,
  openAuthDetails: OpenAuthDetails) extends Identity {

  require(userId >= LowestAuthenticatedUserId)
}


case class OpenAuthDetails(
  providerId: String,
  providerKey: String,
  firstName: Option[String] = None,
  lastName: Option[String] = None,
  fullName: Option[String] = None,
  email: Option[String] = None,
  avatarUrl: Option[String] = None) {

  def providerIdAndKey = OpenAuthProviderIdKey(providerId, providerKey)
  def displayName = firstName.orElse(fullName).getOrElse("(unknown name)")
}


case class OpenAuthProviderIdKey(providerId: String, providerKey: String)


case class LoginGrant(
   identity: Option[Identity],
   user: Member,
   isNewIdentity: Boolean,
   isNewRole: Boolean) {

  require(identity.map(_.id.contains('?')) != Some(true))
  require(identity.map(_.userId == user.id) != Some(false))
  require(!isNewRole || isNewIdentity)
}


/** An IP number or a browser id cookie that has been blocked and may not
  * post comments or vote.
  */
case class Block(
  threatLevel: ThreatLevel,
  ip: Option[jn.InetAddress],
  browserIdCookie: Option[String],
  blockedById: UserId,
  blockedAt: ju.Date,
  blockedTill: Option[ju.Date]) {

  require(browserIdCookie.isDefined, "DwE5KGU8") // change to String then, not Option[String]?
  require(blockedTill.isEmpty || blockedTill.get.getTime >= blockedAt.getTime, "Dwe2KWC8")
  require(browserIdCookie.map(_.trim.isEmpty) != Some(true), "DwE4FUK7")

  def isActiveAt(time: UnixMillis): Boolean = blockedTill match {
    case None => true
    case Some(date) => time <= date.getTime
  }

}


class BlockedTillMap(
  datesByIp: Map[InetAddress, ju.Date],
  datesByBrowserIdCookie: Map[String, ju.Date]) {

  private val blockedTillMap = mutable.HashMap[String, ju.Date]()

  for ((ip, date) <- datesByIp) {
    blockedTillMap.put(ip.toString, date)
  }

  for ((id, date) <- datesByBrowserIdCookie) {
    blockedTillMap.put(id, date)
  }

  def blockedTill(ip: InetAddress): Option[ju.Date] =
    blockedTillMap.get(ip.toString)

  def blockedTill(browserIdCookie: String): Option[ju.Date] =
    blockedTillMap.get(browserIdCookie)

}



case class BrowserIdData(ip: String, idCookie: String, fingerprint: Int) {
  require(ip.nonEmpty, "DwE6G9F0")
  require(idCookie.nonEmpty, "DwE3GJ79")

  def inetAddress = com.google.common.net.InetAddresses.forString(ip)

}
