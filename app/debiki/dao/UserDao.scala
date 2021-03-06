/**
 * Copyright (C) 2012 Kaj Magnus Lindberg (born 1979)
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

package debiki.dao

import com.debiki.core._
import debiki.DebikiHttp.{throwNotFound, throwForbidden}
import java.{util => ju}
import debiki.{BrowserId, SidStatus, DebikiSecurity}
import io.efdi.server.{UserAndLevels, Who}
import scala.collection.immutable
import Prelude._
import EmailNotfPrefs.EmailNotfPrefs
import CachingDao.{CacheKey, CacheValueIgnoreVersion}


trait UserDao {
  self: SiteDao =>


  def insertInvite(invite: Invite) {
    readWriteTransaction { transaction =>
      transaction.insertInvite(invite)
    }
  }


  /** Returns: (CompleteUser, Invite, hasBeenAcceptedAlready: Boolean)
    */
  def acceptInviteCreateUser(secretKey: String): (CompleteUser, Invite, Boolean) = {
    readWriteTransaction { transaction =>
      var invite = transaction.loadInvite(secretKey) getOrElse throwForbidden(
        "DwE6FKQ2", "Bad invite key")

      invite.acceptedAt foreach { acceptedAt =>
        val millisAgo = (new ju.Date).getTime - acceptedAt.getTime
        // For now: If the invitation is < 1 day old, allow the user to log in
        // again via the invitation link. In Discourse, this timeout is configurable.
        if (millisAgo < 24 * 3600 * 1000) {
          val user = loadCompleteUser(invite.userId getOrDie "DwE6FKEW2") getOrDie "DwE8KES2"
          return (user, invite, true)
        }

        throwForbidden("DwE0FKW2", "You have joined the site already, but this link has expired")
      }

      if (transaction.loadMemberByEmailOrUsername(invite.emailAddress).isDefined)
        throwForbidden("DwE8KFG4", o"""You have joined this site already, so this
             join-site invitation link does nothing. Thanks for clicking it anyway""")

      val userId = transaction.nextAuthenticatedUserId
      var newUser = invite.makeUser(userId, transaction.currentTime)
      val inviter = transaction.loadUser(invite.createdById) getOrDie "DwE5FKG4"
      if (inviter.isStaff) {
        newUser = newUser.copy(
          isApproved = Some(true),
          approvedAt = Some(transaction.currentTime),
          approvedById = Some(invite.createdById))
      }

      invite = invite.copy(acceptedAt = Some(transaction.currentTime), userId = Some(userId))

      // COULD loop and append 1, 2, 3, ... until there's no username clash.
      transaction.insertAuthenticatedUser(newUser)
      transaction.updateInvite(invite)
      (newUser, invite, false)
    }
  }


  def approveUser(userId: UserId, approverId: UserId) {
    approveRejectUndoUser(userId, approverId = approverId, isapproved = Some(true))
  }


  def rejectUser(userId: UserId, approverId: UserId) {
    approveRejectUndoUser(userId, approverId = approverId, isapproved = Some(false))
  }


  def undoApproveOrRejectUser(userId: UserId, approverId: UserId) {
    approveRejectUndoUser(userId, approverId = approverId, isapproved = None)
  }


  private def approveRejectUndoUser(userId: UserId, approverId: UserId,
        isapproved: Option[Boolean]) {
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)
      user = user.copy(
        isApproved = isapproved,
        approvedAt = Some(transaction.currentTime),
        approvedById = Some(approverId))
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(userId)
  }


  def setStaffFlags(userId: UserId, isAdmin: Option[Boolean] = None,
        isModerator: Option[Boolean] = None, changedById: UserId) {
    require(isAdmin.isDefined != isModerator.isDefined, "DwE4KEP20")
    if (userId == changedById)
      throwForbidden("DwE4KEF2", "Cannot change one's own is-admin and is-moderator state")

    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)

      if (user.isSuspendedAt(transaction.currentTime) && (
          isAdmin == Some(true) || isModerator == Some(true)))
        throwForbidden("DwE2KEP8", "User is suspended")

      user = user.copy(
        isAdmin = isAdmin.getOrElse(user.isAdmin),
        isModerator = isModerator.getOrElse(user.isModerator))
      // COULD update audit log.
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(userId)
  }


  def lockMemberThreatLevel(memberId: UserId, newThreatLevel: Option[ThreatLevel]) {
    readWriteTransaction { transaction =>
      val member: CompleteUser = transaction.loadTheCompleteUser(memberId)
      val memberAfter = member.copy(lockedThreatLevel = newThreatLevel)
      transaction.updateCompleteUser(memberAfter)
    }
  }


  def lockGuestThreatLevel(guestId: UserId, newThreatLevel: Option[ThreatLevel]) {
    readWriteTransaction { transaction =>
      val guest = transaction.loadTheGuest(guestId)
      ??? // lock both ips and guest cookie
    }
  }


  def suspendUser(userId: UserId, numDays: Int, reason: String, suspendedById: UserId) {
    require(numDays >= 1, "DwE4PKF8")
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)
      if (user.isAdmin)
        throwForbidden("DwE4KEF24", "Cannot suspend admins")

      val suspendedTill = new ju.Date(transaction.currentTime.getTime + numDays * MillisPerDay)
      user = user.copy(
        suspendedAt = Some(transaction.currentTime),
        suspendedTill = Some(suspendedTill),
        suspendedById = Some(suspendedById),
        suspendedReason = Some(reason.trim))
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(userId)
  }


  def unsuspendUser(userId: UserId) {
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)
      user = user.copy(suspendedAt = None, suspendedTill = None, suspendedById = None,
        suspendedReason = None)
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(userId)
  }


  def blockGuest(postId: UniquePostId, numDays: Int, threatLevel: ThreatLevel, blockerId: UserId) {
    readWriteTransaction { transaction =>
      val auditLogEntry: AuditLogEntry = transaction.loadCreatePostAuditLogEntry(postId) getOrElse {
        throwForbidden("DwE2WKF5", "Cannot block user: No audit log entry, so no ip and id cookie")
      }

      if (!User.isGuestId(auditLogEntry.doerId))
        throwForbidden("DwE4WKQ2", "Cannot block authenticated users. Suspend them instead")

      // Hardcode 2 & 6 weeks for now. Asking the user to choose # days –> too much for him/her
      // to think about. Block the ip for a little bit shorter time, because might affect
      // "innocent" people.
      val ipBlockedTill =
        Some(new ju.Date(transaction.currentTime.getTime + OneWeekInMillis * 2))

      val cookieBlockedTill =
        Some(new ju.Date(transaction.currentTime.getTime + OneWeekInMillis * 6))

      val ipBlock = Block(
        threatLevel = threatLevel,
        ip = Some(auditLogEntry.browserIdData.inetAddress),
        browserIdCookie = Some(auditLogEntry.browserIdData.idCookie),
        blockedById = blockerId,
        blockedAt = transaction.currentTime,
        blockedTill = ipBlockedTill)

      val browserIdCookieBlock = Block(
        threatLevel = threatLevel,
        ip = None,
        browserIdCookie = Some(auditLogEntry.browserIdData.idCookie),
        blockedById = blockerId,
        blockedAt = transaction.currentTime,
        blockedTill = cookieBlockedTill)

      // COULD catch dupl key error when inserting IP block, and update it instead, if new
      // threat level is *worse* [6YF42]. Aand continue anyway with inserting browser id
      // cookie block.
      transaction.insertBlock(ipBlock)
      transaction.insertBlock(browserIdCookieBlock)

      // Also set the user's threat level, if the new level is worse.
      transaction.loadGuest(auditLogEntry.doerId) foreach { guest =>
        if (!guest.lockedThreatLevel.exists(_.toInt > threatLevel.toInt)) {
          transaction.updateGuest(
            guest.copy(lockedThreatLevel = Some(threatLevel)))
        }
      }
    }
  }


  def unblockGuest(postNr: PostNr, unblockerId: UserId) {
    readWriteTransaction { transaction =>
      val auditLogEntry: AuditLogEntry = transaction.loadCreatePostAuditLogEntry(postNr) getOrElse {
        throwForbidden("DwE5FK83", "Cannot unblock guest: No audit log entry, IP unknown")
      }
      transaction.unblockIp(auditLogEntry.browserIdData.inetAddress)
      transaction.unblockBrowser(auditLogEntry.browserIdData.idCookie)
      transaction.loadGuest(auditLogEntry.doerId) foreach { guest =>
        if (guest.lockedThreatLevel.isDefined) {
          transaction.updateGuest(guest.copy(lockedThreatLevel = None))
        }
      }
    }
  }


  def loadAuthorBlocks(postId: UniquePostId): immutable.Seq[Block] = {
    readOnlyTransaction { transaction =>
      val auditLogEntry = transaction.loadCreatePostAuditLogEntry(postId) getOrElse {
        return Nil
      }
      val browserIdData = auditLogEntry.browserIdData
      transaction.loadBlocks(ip = browserIdData.ip, browserIdCookie = browserIdData.idCookie)
    }
  }


  def loadBlocks(ip: String, browserIdCookie: String): immutable.Seq[Block] = {
    readOnlyTransactionNotSerializable { transaction =>
      transaction.loadBlocks(ip = ip, browserIdCookie = browserIdCookie)
    }
  }


  def loadUserAndLevels(who: Who, transaction: SiteTransaction) = {
    val user = transaction.loadTheUser(who.id)
    val trustLevel = user match {
      case member: Member => member.effectiveTrustLevel
      case _: Guest => TrustLevel.New
    }
    val threatLevel = user match {
      case member: Member => member.effectiveThreatLevel
      case guest: Guest =>
        val blocks = transaction.loadBlocks(ip = who.ip, browserIdCookie = who.idCookie)
        val baseThreatLevel = guest.lockedThreatLevel getOrElse ThreatLevel.HopefullySafe
        val levelInt = blocks.foldLeft(baseThreatLevel.toInt) { (maxSoFar, block) =>
          math.max(maxSoFar, block.threatLevel.toInt)
        }
        ThreatLevel.fromInt(levelInt) getOrDie "EsE8GY25"
    }
    UserAndLevels(user, trustLevel, threatLevel)
  }


  def createIdentityUserAndLogin(newUserData: NewUserData): LoginGrant = {
    readWriteTransaction { transaction =>
      val userId = transaction.nextAuthenticatedUserId
      val user = newUserData.makeUser(userId, transaction.currentTime)
      val identityId = transaction.nextIdentityId
      val identity = newUserData.makeIdentity(userId = userId, identityId = identityId)
      transaction.insertAuthenticatedUser(user)
      transaction.insertIdentity(identity)
      LoginGrant(Some(identity), user.briefUser, isNewIdentity = true, isNewRole = true)
    }
  }


  /** Used if a user without any matching identity has been created (e.g. because
    * you signup as an email + password user, or accept an invitation). And you then
    * later on try to login via e.g. a Gmail account with the same email address.
    * Then we want to create a Gmail OpenAuth identity and connect it to the user
    * in the database.
    */
  def createIdentityConnectToUserAndLogin(user: Member, oauthDetails: OpenAuthDetails)
        : LoginGrant = {
    require(user.email.nonEmpty, "DwE3KEF7")
    require(user.emailVerifiedAt.nonEmpty, "DwE5KGE2")
    require(user.isAuthenticated, "DwE4KEF8")
    readWriteTransaction { transaction =>
      val identityId = transaction.nextIdentityId
      val identity = OpenAuthIdentity(id = identityId, userId = user.id, oauthDetails)
      transaction.insertIdentity(identity)
      LoginGrant(Some(identity), user, isNewIdentity = true, isNewRole = false)
    }
  }


  def createPasswordUserCheckPasswordStrong(userData: NewPasswordUserData): Member = {
    DebikiSecurity.throwErrorIfPasswordTooWeak(
      password = userData.password, username = userData.username,
      fullName = userData.name, email = userData.email)
    readWriteTransaction { transaction =>
      val userId = transaction.nextAuthenticatedUserId
      val user = userData.makeUser(userId, transaction.currentTime)
      transaction.insertAuthenticatedUser(user)
      user.briefUser
    }
  }


  def changePasswordCheckStrongEnough(userId: UserId, newPassword: String): Boolean = {
    val newPasswordSaltHash = DbDao.saltAndHashPassword(newPassword)
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)
      DebikiSecurity.throwErrorIfPasswordTooWeak(
        password = newPassword, username = user.username,
        fullName = user.fullName, email = user.emailAddress)
      user = user.copy(passwordHash = Some(newPasswordSaltHash))
      transaction.updateCompleteUser(user)
    }
  }


  def loginAsGuest(loginAttempt: GuestLoginAttempt): Guest = {
    readWriteTransaction { transaction =>
      transaction.loginAsGuest(loginAttempt).guest
    }
  }


  def tryLogin(loginAttempt: LoginAttempt): LoginGrant = {
    readWriteTransaction { transaction =>
      val loginGrant = transaction.tryLogin(loginAttempt)
      if (!loginGrant.user.isSuspendedAt(loginAttempt.date))
        return loginGrant

      val user = transaction.loadCompleteUser(loginGrant.user.id) getOrElse throwForbidden(
        "DwE05KW2", "User not found, id: " + loginGrant.user.id)
      // Still suspended?
      if (user.suspendedAt.isDefined) {
        val forHowLong = user.suspendedTill match {
          case None => "forever"
          case Some(date) => "until " + toIso8601(date)
        }
        throwForbidden("DwE403SP0", o"""Account suspended $forHowLong,
            reason: ${user.suspendedReason getOrElse "?"}""")
      }
      loginGrant
    }
  }


  def loadUsers(): immutable.Seq[User] = {
    readOnlyTransaction { transaction =>
      transaction.loadUsers()
    }
  }


  def loadCompleteUsers(onlyThosePendingApproval: Boolean): immutable.Seq[CompleteUser] = {
    readOnlyTransaction { transaction =>
      transaction.loadCompleteUsers(onlyPendingApproval = onlyThosePendingApproval)
    }
  }


  def loadCompleteUser(userId: UserId): Option[CompleteUser] = {
    readOnlyTransaction { transaction =>
      transaction.loadCompleteUser(userId)
    }
  }


  def loadMember(userId: UserId): Option[Member] = {
    require(userId >= User.LowestMemberId, "EsE7YKF2")
    loadUser(userId).map(_.asInstanceOf[Member])
  }


  def loadUser(userId: UserId): Option[User] = {
    readOnlyTransaction { transaction =>
      transaction.loadUser(userId)
    }
  }


  def loadMemberByEmailOrUsername(emailOrUsername: String): Option[Member] = {
    readOnlyTransaction { transaction =>
      // Don't need to cache this? Only called when logging in.
      transaction.loadMemberByEmailOrUsername(emailOrUsername)
    }
  }


  def loadUserAndAnyIdentity(userId: UserId): Option[(Option[Identity], User)] = {
    loadIdtyDetailsAndUser(userId) match {
      case Some((identity, user)) => Some((Some(identity), user))
      case None =>
        // No OAuth or OpenID identity, try load password user:
        loadUser(userId) match {
          case Some(user) =>
            Some((None, user))
          case None =>
            None
        }
    }
  }


  private def loadIdtyDetailsAndUser(userId: UserId): Option[(Identity, User)] = {
    // Don't cache this, because this function is rarely called
    // — currently only when creating new website.
    readOnlyTransaction { transaction =>
      transaction.loadIdtyDetailsAndUser(userId)
    }
  }


  def loadUserInfoAndStats(userId: UserId): Option[UserInfoAndStats] =
    readOnlyTransaction(_.loadUserInfoAndStats(userId))


  def listUserActions(userId: UserId): Seq[UserActionInfo] =
    readOnlyTransaction(_.listUserActions(userId))


  def verifyEmail(userId: UserId, verifiedAt: ju.Date) {
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)
      user = user.copy(emailVerifiedAt = Some(verifiedAt))
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(userId)
  }


  def setUserAvatar(userId: UserId, tinyAvatar: Option[UploadRef], smallAvatar: Option[UploadRef],
        mediumAvatar: Option[UploadRef], browserIdData: BrowserIdData) {
    require(smallAvatar.isDefined == tinyAvatar.isDefined, "EsE9PYM2")
    require(smallAvatar.isDefined == mediumAvatar.isDefined, "EsE8YFM2")
    readWriteTransaction { transaction =>
      val userBefore = transaction.loadTheCompleteUser(userId)
      val userAfter = userBefore.copy(
        tinyAvatar = tinyAvatar,
        smallAvatar = smallAvatar,
        mediumAvatar = mediumAvatar)

      val hasNewAvatar =
        userBefore.tinyAvatar != userAfter.tinyAvatar ||
          userBefore.smallAvatar != userAfter.smallAvatar ||
          userBefore.mediumAvatar != userAfter.mediumAvatar

      val relevantRefs =
        if (!hasNewAvatar) Set.empty
        else
          userBefore.tinyAvatar.toSet ++ userBefore.smallAvatar.toSet ++
            userBefore.mediumAvatar.toSet ++ userAfter.tinyAvatar.toSet ++
            userAfter.smallAvatar.toSet ++ userAfter.mediumAvatar.toSet
      val refsInUseBefore = transaction.filterUploadRefsInUse(relevantRefs)

      transaction.updateCompleteUser(userAfter)

      if (hasNewAvatar) {
        val refsInUseAfter = transaction.filterUploadRefsInUse(relevantRefs)
        val refsAdded = refsInUseAfter -- refsInUseBefore
        val refsRemoved = refsInUseBefore -- refsInUseAfter
        refsAdded.foreach(transaction.updateUploadQuotaUse(_, wasAdded = true))
        refsRemoved.foreach(transaction.updateUploadQuotaUse(_, wasAdded = false))

        userBefore.tinyAvatar.foreach(transaction.updateUploadedFileReferenceCount)
        userBefore.smallAvatar.foreach(transaction.updateUploadedFileReferenceCount)
        userBefore.mediumAvatar.foreach(transaction.updateUploadedFileReferenceCount)
        userAfter.tinyAvatar.foreach(transaction.updateUploadedFileReferenceCount)
        userAfter.smallAvatar.foreach(transaction.updateUploadedFileReferenceCount)
        userAfter.mediumAvatar.foreach(transaction.updateUploadedFileReferenceCount)
        transaction.markPagesWithUserAvatarAsStale(userId)
      }
    }
    refreshUserInAnyCache(userId)
    // Clear the PageStuff cache (by clearing the whole in-mem cache), because
    // PageStuff includes avatar urls.
    // COULD have above markPagesWithUserAvatarAsStale() return a page id list and
    // uncache only those pages.
    emptyCache()
  }


  def configRole(userId: RoleId,
        emailNotfPrefs: Option[EmailNotfPrefs] = None, isAdmin: Option[Boolean] = None,
        isOwner: Option[Boolean] = None) {
    // Don't specify emailVerifiedAt here — use verifyEmail() instead; it refreshes the cache.
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(userId)
      emailNotfPrefs foreach { prefs =>
        user = user.copy(emailNotfPrefs = prefs)
      }
      isAdmin foreach { isAdmin =>
        user = user.copy(isAdmin = isAdmin)
      }
      isOwner foreach { isOwner =>
        user = user.copy(isOwner = isOwner)
      }
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(userId)
  }


  def configIdtySimple(ctime: ju.Date, emailAddr: String, emailNotfPrefs: EmailNotfPrefs) = {
    readWriteTransaction { transaction =>
      transaction.configIdtySimple(ctime = ctime,
        emailAddr = emailAddr, emailNotfPrefs = emailNotfPrefs)
      // COULD refresh guest in cache: new email prefs --> perhaps show "??" not "?" after name.
    }
  }


  def listUsers(): Seq[User] =
    readOnlyTransaction(_.loadUsers())


  def listUsernames(pageId: PageId, prefix: String): Seq[NameAndUsername] =
    readOnlyTransaction(_.listUsernames(pageId = pageId, prefix = prefix))


  def loadUserIdsWatchingPage(pageId: PageId): Seq[UserId] =
    readOnlyTransaction(_.loadUserIdsWatchingPage(pageId))


  def loadRolePageSettings(roleId: RoleId, pageId: PageId): RolePageSettings =
    readOnlyTransaction(_.loadRolePageSettings(roleId = roleId, pageId = pageId)) getOrElse
      RolePageSettings.Default


  def saveRolePageSettings(roleId: RoleId, pageId: PageId, settings: RolePageSettings) =
    readWriteTransaction(_.saveRolePageSettings(roleId = roleId, pageId = pageId, settings))


  def saveRolePreferences(preferences: UserPreferences) = {
    // BUG: the lost update bug.
    readWriteTransaction { transaction =>
      var user = transaction.loadTheCompleteUser(preferences.userId)

      // For now, don't allow people to change their username. In the future, changing
      // it should be alloowed, but only very infrequently? Or only the very first few days.
      if (user.username != preferences.username)
        throwForbidden("DwE44ELK9", "Must not modify one's username")

      // For now, don't allow the user to change his/her email. I haven't
      // implemented any related security checks, e.g. verifying with the old address
      // that this is okay, or sending an address confirmation email to the new address.
      if (user.emailAddress != preferences.emailAddress)
        throwForbidden("DwE44ELK9", "Must not modify one's email")

      user = user.copyWithNewPreferences(preferences)
      transaction.updateCompleteUser(user)
    }
    refreshUserInAnyCache(preferences.userId)
  }


  def saveGuest(guestId: UserId, name: String) {
    // BUG: the lost update bug.
    readWriteTransaction { transaction =>
      var guest = transaction.loadTheGuest(guestId)
      guest = guest.copy(guestName = name)
      transaction.updateGuest(guest)
    }
    refreshUserInAnyCache(guestId)
  }


  def perhapsBlockGuest(request: play.api.mvc.Request[_], sidStatus: SidStatus,
        browserId: BrowserId) {
    if (request.method == "GET")
      return

    // Authenticated users are ignored here. Suspend them instead.
    if (sidStatus.userId.map(User.isRoleId) == Some(true))
      return

    // Ignore not-logged-in people, unless they attempt to login as guests.
    if (sidStatus.userId.isEmpty) {
      val guestLoginPath = controllers.routes.LoginAsGuestController.loginGuest().url
      if (!request.path.contains(guestLoginPath))
        return
    }

    // COULD cache blocks, but not really needed since this is for post requests only.
    val blocks = loadBlocks(
      ip = request.remoteAddress,
      // COULD pass None not ""?
      browserIdCookie = if (browserId.isNew) "-" else browserId.cookieValue)

    val nowMillis = System.currentTimeMillis
    for (block <- blocks) {
      if (block.isActiveAt(nowMillis) && block.threatLevel == ThreatLevel.SevereThreat)
        throwForbidden("DwE403BK01", o"""Not allowed. Please sign up with a username
            and password, or login with Google or Facebook, for example.""")
    }
  }


  def refreshUserInAnyCache(userId: UserId) {
  }

}



trait CachingUserDao extends UserDao {
  self: CachingSiteDao =>


  override def createIdentityUserAndLogin(newUserData: NewUserData): LoginGrant = {
    val loginGrant = super.createIdentityUserAndLogin(newUserData)
    fireUserCreated(loginGrant.user)
    loginGrant
  }


  override def createPasswordUserCheckPasswordStrong(userData: NewPasswordUserData): Member = {
    val user = super.createPasswordUserCheckPasswordStrong(userData)
    fireUserCreated(user)
    user
  }


  override def loginAsGuest(loginAttempt: GuestLoginAttempt): Guest = {
    val user = super.loginAsGuest(loginAttempt)
    putInCache(
      key(user.id),
      CacheValueIgnoreVersion(user))
    user
  }


  override def tryLogin(loginAttempt: LoginAttempt): LoginGrant = {
    // Don't save any site cache version, because user specific data doesn't change
    // when site specific data changes.
    val loginGrant = super.tryLogin(loginAttempt)
    putInCache(
      key(loginGrant.user.id),
      CacheValueIgnoreVersion(loginGrant.user))
    loginGrant
  }


  override def loadUser(userId: UserId): Option[User] = {
    lookupInCache[User](
      key(userId),
      orCacheAndReturn = super.loadUser(userId),
      ignoreSiteCacheVersion = true)
  }


  override def refreshUserInAnyCache(userId: UserId) {
    removeFromCache(key(userId))
  }


  private def key(userId: UserId) = CacheKey(siteId, s"$userId|UserById")

}


