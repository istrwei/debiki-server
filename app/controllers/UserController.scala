/**
 * Copyright (C) 2014 Kaj Magnus Lindberg (born 1979)
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

package controllers

import com.debiki.core._
import com.debiki.core.Prelude._
import com.debiki.core.User.{isGuestId, MinUsernameLength}
import debiki._
import debiki.ReactJson._
import io.efdi.server.http._
import java.{util => ju}
import play.api.mvc
import play.api.libs.json._
import play.api.mvc.{Action => _, _}
import scala.util.Try
import DebikiHttp._


/** Handles requests related to users.
 */
object UserController extends mvc.Controller {


  def listCompleteUsers(whichUsers: String) = StaffGetAction { request =>
    var onlyApproved = false
    var onlyPendingApproval = false
    whichUsers match {
      case "ActiveUsers" =>
        onlyApproved = request.dao.loadWholeSiteSettings().userMustBeApproved
      case "NewUsers" =>
        onlyPendingApproval = true
    }
    request.dao.readOnlyTransaction { transaction =>
      val usersPendingApproval = transaction.loadCompleteUsers(
        onlyApproved = onlyApproved,
        onlyPendingApproval = onlyPendingApproval)
      val approverIds = usersPendingApproval.flatMap(_.approvedById)
      val suspenderIds = usersPendingApproval.flatMap(_.suspendedById)
      val usersById = transaction.loadMembersAsMap(approverIds ++ suspenderIds)
      val usersJson = JsArray(usersPendingApproval.map(
        jsonForCompleteUser(_, usersById, callerIsAdmin = request.theUser.isAdmin,
          callerIsStaff = true)))
      OkSafeJson(Json.toJson(Map("users" -> usersJson)))
    }
  }


  def loadCompleteUser(userId: String) = GetAction { request =>
    val userIdInt = Try(userId.toInt) getOrElse throwBadReq("DwE6FWV0", "Bad user id")
    val callerIsStaff = request.user.exists(_.isStaff)
    val callerIsAdmin = request.user.exists(_.isAdmin)
    val callerIsUserHerself = request.user.map(_.id == userIdInt) == Some(true)
    request.dao.readOnlyTransaction { transaction =>
      val usersJson =
        if (User.isRoleId(userIdInt)) {
          val user = transaction.loadTheCompleteUser(userIdInt)
          jsonForCompleteUser(user, Map.empty, callerIsAdmin = callerIsAdmin,
            callerIsStaff = callerIsStaff, callerIsUserHerself = callerIsUserHerself)
        }
        else {
          val user = transaction.loadTheGuest(userIdInt)
          jsonForGuest(user, Map.empty, callerIsStaff = callerIsStaff,
            callerIsAdmin = callerIsAdmin)

        }
      OkSafeJson(Json.toJson(Map("user" -> usersJson)))
    }
  }


  private def jsonForCompleteUser(user: CompleteUser, usersById: Map[UserId, Member],
      callerIsAdmin: Boolean, callerIsStaff: Boolean = false, callerIsUserHerself: Boolean = false)
        : JsObject = {
    var userJson = Json.obj(
      "id" -> user.id,
      "createdAtEpoch" -> JsNumber(user.createdAt.getTime),
      "username" -> user.username,
      "fullName" -> user.fullName,
      "isAdmin" -> user.isAdmin,
      "isModerator" -> user.isModerator,
      "country" -> user.country,
      "url" -> user.website,
      "avatarUrl" -> JsUploadUrlOrNull(user.smallAvatar),
      "mediumAvatarUrl" -> JsUploadUrlOrNull(user.mediumAvatar),
      "suspendedTillEpoch" -> DateEpochOrNull(user.suspendedTill))

    if (callerIsStaff || callerIsUserHerself) {
      val anyApprover = user.approvedById.flatMap(usersById.get)
      val safeEmail =
        if (callerIsAdmin || callerIsUserHerself) user.emailAddress
        else hideEmailLocalPart(user.emailAddress)

      userJson += "email" -> JsString(safeEmail)
      userJson += "emailForEveryNewPost" -> JsBoolean(user.emailForEveryNewPost)
      userJson += "isApproved" -> JsBooleanOrNull(user.isApproved)
      userJson += "approvedAtEpoch" -> DateEpochOrNull(user.approvedAt)
      userJson += "approvedById" -> JsNumberOrNull(user.approvedById)
      userJson += "approvedByName" -> JsStringOrNull(anyApprover.flatMap(_.fullName))
      userJson += "approvedByUsername" -> JsStringOrNull(anyApprover.flatMap(_.username))
      userJson += "suspendedAtEpoch" -> DateEpochOrNull(user.suspendedAt)
      userJson += "suspendedReason" -> JsStringOrNull(user.suspendedReason)
    }

    if (callerIsStaff) {
      val anySuspender = user.suspendedById.flatMap(usersById.get)
      userJson += "suspendedById" -> JsNumberOrNull(user.suspendedById)
      userJson += "suspendedByUsername" -> JsStringOrNull(anySuspender.flatMap(_.username))
      userJson += "trustLevel" -> JsNumber(user.trustLevel.toInt)
      userJson += "lockedTrustLevel" -> JsNumberOrNull(user.lockedTrustLevel.map(_.toInt))
      userJson += "threatLevel" -> JsNumber(user.threatLevel.toInt)
      userJson += "lockedThreatLevel" -> JsNumberOrNull(user.lockedThreatLevel.map(_.toInt))
    }
    userJson
  }


  private def jsonForGuest(user: Guest, usersById: Map[UserId, User],
        callerIsStaff: Boolean, callerIsAdmin: Boolean): JsObject = {
    val safeEmail = callerIsAdmin ? user.email | hideEmailLocalPart(user.email)
    var userJson = Json.obj(
      "id" -> user.id,
      "fullName" -> user.guestName,
      "country" -> user.country,
      "url" -> user.website)
      // += ipSuspendedTill
      // += browserIdCookieSuspendedTill
    if (callerIsStaff) {
      userJson += "email" -> JsString(safeEmail)
      // += ipSuspendedAt, ById, ByUsername, Reason
      // += browserIdCookieSuspendedAt, ById, ByUsername, Reason
    }
    userJson
  }


  def approveRejectUser = StaffPostJsonAction(maxLength = 100) { request =>
    val userId = (request.body \ "userId").as[UserId]
    val doWhat = (request.body \ "doWhat").as[String]
    doWhat match {
      case "Approve" =>
        request.dao.approveUser(userId, approverId = request.theUserId)
      case "Reject" =>
        request.dao.rejectUser(userId, approverId = request.theUserId)
      case "Undo" =>
        request.dao.undoApproveOrRejectUser(userId, approverId = request.theUserId)
    }
    Ok
  }


  def setIsAdminOrModerator = AdminPostJsonAction(maxLength = 100) { request =>
    val userId = (request.body \ "userId").as[UserId]
    val doWhat = (request.body \ "doWhat").as[String]
    doWhat match {
      case "GrantAdmin" =>
        request.dao.setStaffFlags(userId, isAdmin = Some(true), changedById = request.theUserId)
      case "RevokeAdmin" =>
        request.dao.setStaffFlags(userId, isAdmin = Some(false), changedById = request.theUserId)
      case "GrantModerator" =>
        request.dao.setStaffFlags(userId, isModerator = Some(true), changedById = request.theUserId)
      case "RevokeModerator" =>
        request.dao.setStaffFlags(userId, isModerator = Some(false), changedById = request.theUserId)
      case _ =>
        throwBadReq("DwE2KGF8", "Bad 'doWhat'")
    }
    Ok
  }


  def lockThreatLevel = StaffPostJsonAction(maxLength = 100) { request =>
    val userId = (request.body \ "userId").as[UserId]
    val threatLevelInt = (request.body \ "threatLevel").as[Int]
    val threatLevel = ThreatLevel.fromInt(threatLevelInt) getOrElse throwBadRequest(
        "EsE2FW40C", s"Bad threat level: $threatLevelInt")
    if (User.isMember(userId)) {
      request.dao.lockMemberThreatLevel(userId, Some(threatLevel))
    }
    else {
      request.dao.lockGuestThreatLevel(userId, Some(threatLevel))
    }
    Ok
  }


  def unlockThreatLevel = StaffPostJsonAction(maxLength = 100) { request =>
    val userId = (request.body \ "userId").as[UserId]
    if (User.isMember(userId)) {
      request.dao.lockMemberThreatLevel(userId, None)
    }
    else {
      request.dao.lockGuestThreatLevel(userId, None)
    }
    Ok
  }


  def suspendUser = StaffPostJsonAction(maxLength = 300) { request =>
    val userId = (request.body \ "userId").as[UserId]
    val numDays = (request.body \ "numDays").as[Int]
    val reason = (request.body \ "reason").as[String]
    if (numDays < 1)
      throwBadReq("DwE4FKW0", "Please specify at least one day")
    if (reason.length > 255)
      throwBadReq("DwE4FKW0", "Too long suspend-user-reason")
    if (isGuestId(userId))
      throwBadReq("DwE5KE8", "Cannot suspend guest user ids")

    request.dao.suspendUser(userId, numDays, reason, suspendedById = request.theUserId)
    Ok
  }


  def unsuspendUser = StaffPostJsonAction(maxLength = 100) { request =>
    val userId = (request.body \ "userId").as[UserId]
    if (isGuestId(userId))
      throwBadReq("DwE7GPKU8", "Cannot unsuspend guest user ids")
    request.dao.unsuspendUser(userId)
    Ok
  }


  def blockGuest = StaffPostJsonAction(maxLength = 100) { request =>
    val postId = (request.body \ "postId").as[UniquePostId]
    val numDays = -1 // (request.body \ "numDays").as[Int] // currently no longer in use
    val threatLevel = ThreatLevel.fromInt((request.body \ "threatLevel").as[Int]).getOrElse(
      throwBadArgument("EsE8GY2W", "threatLevel"))
    request.dao.blockGuest(postId, numDays = numDays, threatLevel, blockerId = request.theUserId)
    Ok
  }


  def unblockGuest = StaffPostJsonAction(maxLength = 100) { request =>
    val postId = (request.body \ "postId").as[UniquePostId]
    request.dao.unblockGuest(postId, unblockerId = request.theUserId)
    Ok
  }


  def loadAuthorBlocks(postId: String) = GetAction { request =>
    val postIdInt = Try(postId.toInt) getOrElse throwBadReq("DwE4WK78", "Bad post id")
    val blocks: Seq[Block] = request.dao.loadAuthorBlocks(postIdInt)
    var json = blocksSummaryJson(blocks, request.ctime)
    if (request.user.map(_.isStaff) == Some(true)) {
      json += "blocks" -> JsArray(blocks map blockToJson)
    }
    OkSafeJson(json)
  }


  private def blocksSummaryJson(blocks: Seq[Block], now: ju.Date): JsObject = {
    var isBlocked = false
    var blockedForever = false
    var maxEndUnixMillis: UnixMillis = 0L
    for (block <- blocks) {
      if (block.blockedTill.isEmpty) {
        isBlocked = true
        blockedForever = true
      }
      else if (now.getTime <= block.blockedTill.get.getTime) {
        isBlocked = true
        maxEndUnixMillis = math.max(maxEndUnixMillis, block.blockedTill.get.getTime)
      }
    }
    var json = Json.obj(
      "isBlocked" -> isBlocked,
      "blockedForever" -> blockedForever)
    if (maxEndUnixMillis != 0L && !blockedForever) {
      json += "blockedTillMs" -> JsNumber(maxEndUnixMillis)
    }
    json
  }


  private def blockToJson(block: Block): JsObject = {
    Json.obj(
      "threatLevel" -> JsNumber(block.threatLevel.toInt),
      "ip" -> JsStringOrNull(block.ip.map(_.toString)),
      "browserIdCookie" -> block.browserIdCookie,
      "blockedById" -> block.blockedById,
      "blockedAtMs" -> block.blockedAt.getTime,
      "blockedTillMs" -> JsLongOrNull(block.blockedTill.map(_.getTime)))
  }


  def viewUserPage(whatever: String) = GetAction { request =>
    val htmlStr = views.html.templates.users(SiteTpi(request)).body
    Ok(htmlStr) as HTML
  }


  def loadUserInfo(userId: String) = GetAction { request =>
    val userIdInt = Try(userId.toInt) getOrElse throwBadReq("DwE4FKf2", "Bad user id")
    val userInfo = request.dao.loadUserInfoAndStats(userIdInt) getOrElse throwNotFound(
      "DwE512WR8", s"User not found, id: $userId")
    val json = Json.obj("userInfo" -> userInfoToJson(userInfo))
    OkSafeJson(json)
  }


  def loadMyPageData(pageId: PageId) = GetAction { request =>
    SECURITY ; COULD // avoid revealing that a page exists: forPageThatExists below might throw
    // a unique NotFound for example.  [7C2KF24]
    val myPageData = PageRequest.forPageThatExists(request, pageId) match {
      case None =>
        // Might be an embedded comment page, not yet created because no comments posted.
        // Or we might be in the signup-to-become-owner step, when creating a new site.
        ReactJson.userNoPageToJson(request)
      case Some(request) =>
        ReactJson.userDataJson(request) getOrElse ReactJson.NoUserSpecificData
    }
    OkSafeJson(myPageData)
  }


  def listUserActions(userId: String) = GetAction { request =>
    throwNotImplemented("DwE4KPE7", "Listing user actions hasn't been implemented") /*
    val userIdInt = Try(userId.toInt) getOrElse throwBadReq("DwE8UKG4", "Bad user id")
    val actionInfos: Seq[UserActionInfo] = request.dao.listUserActions(userIdInt)
    val json = Json.obj("actions" -> actionInfos.map(actionToJson(_)))
    OkSafeJson(json)
    */
  }


  def loadNotifications(userId: String, upToWhenMs: String) = GetAction { request =>
    val userIdInt = userId.toIntOrThrow("EsE5GYK2", "Bad userId")
    val upToWhenMsLong = upToWhenMs.toLongOrThrow("EsE2FUY7", "Bad upToWhenMs")
    val upToWhenDate = new ju.Date(upToWhenMsLong)
    val notfsAndCounts = request.dao.readOnlyTransaction { transaction =>
      ReactJson.loadNotifications(userIdInt, transaction, unseenFirst = false, limit = 100,
        upToWhen = None) // later: Some(upToWhenDate), and change to limit = 50 above?
    }
    OkSafeJson(notfsAndCounts.notfsJson)
  }


  def markNotificationAsSeen() = PostJsonAction(RateLimits.MarkNotfAsSeen, 200) { request =>
    val notfId = (request.body \ "notfId").as[NotificationId]
    request.dao.markNotificationAsSeen(request.theUserId, notfId)
    Ok
  }


  def savePageNotfLevel = PostJsonAction(RateLimits.ConfigUser, maxLength = 500) { request =>
    val body = request.body
    val pageId = (body \ "pageId").as[PageId]
    val newNotfLevelStr = (body \ "pageNotfLevel").as[String]
    val newNotfLevel = PageNotfLevel.fromString(newNotfLevelStr)
    request.dao.saveRolePageSettings(roleId = request.theRoleId, pageId = pageId,
      RolePageSettings(newNotfLevel))
    Ok
  }


  def listUsernames(pageId: PageId, prefix: String) = GetAction { request =>
    val names = request.dao.listUsernames(pageId = pageId, prefix = prefix)
    val json = JsArray(
      names map { nameAndUsername =>
        Json.obj(
          "username" -> nameAndUsername.username,
          "fullName" -> nameAndUsername.fullName)
      })
    OkSafeJson(json)
  }


  def loadUserPreferences(userId: String) = GetAction { request =>
    val userIdInt = Try(userId.toInt) getOrElse throwBadReq("DwE7KBA0", "Bad user id")
    checkUserPrefsAccess(request, userIdInt)
    val user = request.dao.loadCompleteUser(userIdInt) getOrElse throwNotFound(
      "DwE3EJ5O2", s"User not found, id: $userId")
    val prefs = user.preferences
    val json = Json.obj("userPreferences" -> userPrefsToJson(prefs, request.theUser))
    OkSafeJson(json)
  }


  def saveUserPreferences = PostJsonAction(RateLimits.ConfigUser, maxLength = 1000) { request =>
    val prefs = userPrefsFromJson(request.body)
    checkUserPrefsAccess(request, prefs.userId)
    request.dao.saveRolePreferences(prefs)
    Ok
  }


  def saveGuest = PostJsonAction(RateLimits.ConfigUser, maxLength = 300) { request =>
    val guestId = (request.body \ "guestId").as[UserId]
    val name = (request.body \ "name").as[String].trim
    if (name.isEmpty)
      throwForbidden("DwE4KWP9", "No name specified")

    try { request.dao.saveGuest(guestId, name = name) }
    catch {
      case DbDao.DuplicateGuest =>
        throwForbidden("DwE5KQP4", o"""There is another guest with the exact same name
            and other data. Please change the name, e.g. append "2".""")
    }
    Ok
  }


  private def checkUserPrefsAccess(request: DebikiRequest[_], prefsUserId: UserId) {
    val staffOrOwn = request.theUser.isStaff || request.theUserId == prefsUserId
    if (!staffOrOwn)
      throwForbidden("DwE15KFE5", "Not your preferences")
  }


  private def userInfoToJson(userInfo: UserInfoAndStats): JsObject = {
    Json.obj(
      "userId" -> userInfo.info.id,
      "displayName" -> userInfo.info.anyName,
      "username" -> JsStringOrNull(userInfo.info.anyUsername),
      "isAdmin" -> userInfo.info.isAdmin,
      "isModerator" -> userInfo.info.isModerator,
      "numPages" -> userInfo.stats.numPages,
      "numPosts" -> userInfo.stats.numPosts,
      "numReplies" -> userInfo.stats.numReplies,
      "numLikesGiven" -> userInfo.stats.numLikesGiven,
      "numLikesReceived" -> userInfo.stats.numLikesReceived,
      "numWrongsGiven" -> userInfo.stats.numWrongsGiven,
      "numWrongsReceived" -> userInfo.stats.numWrongsReceived,
      "numBurysGiven" -> userInfo.stats.numBurysGiven,
      "numBurysReceived" -> userInfo.stats.numBurysReceived)

    /* Discourse also includes:
      "avatar_template": ...
      "badge_count" : 0,
      "bio_cooked" : "<p>Hi <strong>everybody</strong>! </p>",
      "bio_excerpt" : "Hi everybody!",
      "bio_raw" : "\nHi **everybody**! ",
      "can_edit" : false,
      "can_edit_email" : false,
      "can_edit_name" : false,
      "can_edit_username" : false,
      "can_send_private_message_to_user" : true,
      "created_at" : "2013-02-17T15:09:06.675-05:00",
       group membership info
      "featured_user_badge_ids" : [  ],
      "invited_by" : null,
      "last_posted_at" : "2014-05-10T02:47:06.860-04:00",
      "last_seen_at" : "2014-05-10T03:42:16.842-04:00",
      "profile_background" : "/uploads/default/4870/f95c8f5b0817f799.jpg",
      "stats" : [ { "action_type" : 4,
              "count" : 5,
              "id" : null
            },
            { "action_type" : 5,
              "count" : 217,
              "id" : null
            },
            ... 11 stats
          ],
        "title" : "designerator",
        "trust_level" : 2,
        "username" : "awesomerobot",
        "website" : "https://"
      },
      "user_badges" : [ ]
     */
  }


  private def actionToJson(actionInfo: UserActionInfo): JsObject = {
    Json.obj(
      "pageUrl" -> s"/-${actionInfo.pageId}", // redirects to the page
      "pageTitle" -> JsString(actionInfo.pageTitle),
      "postId" -> JsNumber(actionInfo.postNr),
      "actionId" -> JsNumber(actionInfo.actionId),
      "actingUserId" -> JsNumber(actionInfo.actingUserId),
      "actingUserDisplayName" -> JsString(actionInfo.actingUserDisplayName),
      "targetUserId" -> JsNumber(actionInfo.targetUserId),
      "targetUserDisplayName" -> JsString(actionInfo.targetUserDisplayName),
      "createdAtEpoch" -> JsNumber(actionInfo.createdAt.getTime),
      "excerpt" -> JsString(actionInfo.postExcerpt),
      "repliedToPostId" -> actionInfo.repliedToPostNr.map(JsNumber(_)),
      "editedPostId" -> actionInfo.editedPostNr.map(JsNumber(_)),
      "approved" -> JsBoolean(actionInfo.approved),
      "deleted" -> JsBoolean(actionInfo.deleted),
      "pinned" -> JsBoolean(actionInfo.pinned),
      "collapsed" -> JsBoolean(actionInfo.collapsed),
      "closed" -> JsBoolean(actionInfo.closed),
      "votedLike" -> JsBoolean(actionInfo.votedLike),
      "votedWrong" -> JsBoolean(actionInfo.votedWrong),
      "votedBury" -> JsBoolean(actionInfo.votedBury))
    /* Discourse also includes:
      - usernames
      - the user that wrote the relevant post (avatar, display name, username, id)
      - action type (instead of votedLike, repliedTo...)
      - avatars: "//www.gravatar.com/avatar/....png?s={size}&r=pg&d=identicon",
      - deleted : false,
      - edit_reason : null,
      - hidden : false,
      - moderator_action : false,
     */
  }


  private def userPrefsToJson(prefs: UserPreferences, requester: User): JsObject = {
    val adminOrOwn = requester.isAdmin || prefs.userId == requester.id
    val safeEmail = adminOrOwn ? prefs.emailAddress | hideEmailLocalPart(prefs.emailAddress)
    Json.obj(
      "userId" -> prefs.userId,
      "fullName" -> prefs.fullName,
      "username" -> prefs.username,
      "emailAddress" -> safeEmail,
      "url" -> prefs.url,
      "emailForEveryNewPost" -> prefs.emailForEveryNewPost)
  }


  private def userPrefsFromJson(json: JsValue): UserPreferences = {
    val username = (json \ "username").as[String]
    if (username.length < MinUsernameLength)
      throwBadReq("DwE44KUY0", "Username too short")

    UserPreferences(
      userId = (json \ "userId").as[UserId],
      fullName = (json \ "fullName").asOptStringNoneIfBlank,
      username = username,
      emailAddress = (json \ "emailAddress").as[String],
      url = (json \ "url").as[String],
      emailForEveryNewPost = (json \ "emailForEveryNewPost").as[Boolean])
  }

}

