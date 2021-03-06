/**
 * Copyright (C) 2012-2013 Kaj Magnus Lindberg (born 1979)
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
import debiki._
import io.efdi.server.http._
import java.{util => ju, io => jio}
import play.api._
import play.api.libs.MimeTypes
import play.api.libs.iteratee.Enumerator
import play.api.mvc.{Action => _, _}
import play.api.Play.current
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import DebikiHttp._



/** Miscellaneous controller functions -- try to move elsewhere and/or rename this class
  */
object Application extends mvc.Controller {


  def methodNotAllowed = ExceptionAction { request =>
    MethodNotAllowedResult
  }


  def flag = PostJsonAction(RateLimits.FlagPost, maxLength = 2000) { request =>
    val body = request.body
    val pageId = (body \ "pageId").as[PageId]
    val postNr = (body \ "postId").as[PostNr]
    val typeStr = (body \ "type").as[String]
    val reason = (body \ "reason").as[String]

    val flagType = typeStr match {
      case "Spam" => PostFlagType.Spam
      case "Inapt" => PostFlagType.Inapt
      case "Other" => PostFlagType.Other
      case x => throwBadReq("DwE7PKTS3", s"Bad flag type: '$x'")
    }

    // SHOULD hide post, since flagged (at least if >= 2 flags?)
    // COULD save `reason` somewhere, but where? Where does Discourse save it?
    // SHOULD generate notification

    request.dao.flagPost(pageId = pageId, postNr = postNr, flagType,
      flaggerId = request.theUser.id)

    val json = ReactJson.postToJson2(postNr = postNr, pageId = pageId, dao = request.dao)
    OkSafeJson(json)
  }


  /**
   * Usage example:
   *   /some/site/section/?feed=atom&for-tree&limit=X&partial
   * — this would feed atom for pages below /some/site/section/,
   * the 10 most recent pages only, and only parts of each page
   * would be included (e.g. the first 50 words).
   *
   * However: &limit and &partial | &full haven't been implemented.
   *
   * `limit` may be at most 10.
   * /
  def feed(pathIn: PagePath) = PageGetAction(pathIn, pageMustExist = false) {
        pageReq =>

    throwNotImplemented("DwE5JKP4", "Currently disabled: Atom or RSS feeds, not with new Post2") /*
    import pageReq.{pagePath}

    // The tenant's name will be included in the feed.
    val tenant: Tenant = pageReq.dao.loadTenant()

    val feedPagePaths =
      if (!pagePath.isFolderOrIndexPage) List(pagePath)
      else pageReq.dao.listPagePaths(
        Utils.parsePathRanges(pageReq.pagePath.folder, pageReq.request.queryString,
           urlParamPrefix = "for"),
        include = List(PageStatus.Published),
        orderOffset = PageOrderOffset.ByPublTime,
        limit = 10).map(_.path)

    // Access control.
    // Somewhat dupl code, see AppList.listNewestPages.
    val feedPathsPublic = feedPagePaths filter (Utils.isPublicArticlePage _)

    val pathsAndPages: Seq[(PagePath, PageParts)] = feedPathsPublic flatMap {
      feedPagePath =>
        val pageId: String = feedPagePath.pageId.getOrElse {
          errDbgDie("[error DwE012210u9]")
          "GotNoGuid"
        }
        unimplemented("Loading pages in order to render Atom feeds", "DwE0GY23") /* loadPageParts is gone
        val page = pageReq.dao.loadPageParts(pageId)
        page.map(p => List(feedPagePath -> p)).getOrElse(Nil)
        */
    }

    val mostRecentPageCtime: ju.Date =
      pathsAndPages.headOption.map(pathAndPage =>
        pathAndPage._2.getPost_!(PageParts.BodyId).creationDati
      ).getOrElse(new ju.Date)

    val feedUrl = pageReq.origin + pageReq.request.uri

    val feedXml = AtomFeedXml.renderFeed(
      hostUrl = pageReq.origin,  // should rename hostUrl to origin
      feedId = feedUrl,  // send url path + query instead?
      feedTitle = tenant.name +", "+ pagePath.value,
      feedUpdated = mostRecentPageCtime,
      pathsAndPages)

    OkXml(feedXml, "application/atom+xml")
    */
  } */


  def assetAt(path: String, file: String) = ExceptionAction.async { implicit request =>
    assetAtImpl(path, file, request)
  }


  /** Understands HTTP byte range requests, so that mp4 videos will work on iPhone and iPad.
    * (You could google for "iphone mp4 byte-range".)
    * Inspired by https://groups.google.com/d/msg/play-framework/-BN2eUXtzjI/8_l08euEFvcJ
    * and Samuel Lörtscher's example.
    *
    * Play 2.3 doesn't support byte range requests. Here's some ongoing discussion:
    *   https://github.com/playframework/playframework/issues/1097
    */
  private def assetAtImpl(path: String, file: String, request: Request[AnyContent])
        : Future[Result] = {
    val rangeHeaderValue = request.headers.get(RANGE) getOrElse {
      return controllers.Assets.at(path, file)(request)
    }

    val mimeType = MimeTypes.forFileName(file) getOrElse {
      if (file.endsWith(".m4v")) "video/mp4"
      else throwForbidden("DwE5Kf24", "Unknown file type")
    }

    val stream: jio.InputStream = getClass.getResourceAsStream(s"$path/$file")
    if (stream == null)
      throwNotFound("DwE404ZG4", "File not found")

    // `stream.available` might not be the length of the whole file. Depends on the JVM.
    // But works well for me and my tiny 100kb demo videos. So, for now:
    val streamLength = stream.available
    val startAndEnd: Array[String] = rangeHeaderValue.substring("bytes=".length).split("-")
    val start = startAndEnd(0).toLong
    val end =
      if (startAndEnd.length == 1)
        streamLength - 1
      else
        startAndEnd(1).toLong

    stream.skip(start)
    Future(Result(
      ResponseHeader(PARTIAL_CONTENT, Map[String, String](
        CONNECTION -> "keep-alive",
        CACHE_CONTROL -> "public, max-age=31536000",
        ACCEPT_RANGES -> "bytes",
        CONTENT_RANGE -> s"bytes $start-$end/$streamLength",
        CONTENT_LENGTH -> (end - start + 1).toString,
        CONTENT_TYPE -> mimeType)),
      // Does this assume that the client asked for the whole file? That end == streamLength?
      Enumerator.fromStream(stream)))
  }

}
