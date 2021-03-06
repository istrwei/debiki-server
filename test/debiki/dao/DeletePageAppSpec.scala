/**
 * Copyright (c) 2016 Kaj Magnus Lindberg
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
import debiki.DebikiHttp.ResultException
import debiki._
import io.efdi.server.Who


class DeletePageAppSpec extends DaoAppSuite(disableScripts = true, disableBackgroundJobs = true) {

  "PagesDao can delete pages" - {
    lazy val dao: SiteDao = Globals.siteDao(Site.FirstSiteId)

    lazy val forumId = dao.createForum(title = "Forum to delete", folder = "/",
      Who(SystemUserId, browserIdData)).pagePath.thePageId

    lazy val discussionId = createPage(PageRole.Discussion, TextAndHtml.testTitle("Title"),
      TextAndHtml.testBody("Body text"), authorId = SystemUserId, browserIdData, dao)

    lazy val htmlPageId = createPage(PageRole.CustomHtmlPage, TextAndHtml.testTitle("Title"),
      TextAndHtml.testBody("Body text"), authorId = SystemUserId, browserIdData, dao)

    lazy val otherPageId = createPage(PageRole.Discussion, TextAndHtml.testTitle("Title"),
      TextAndHtml.testBody("Body text"), authorId = SystemUserId, browserIdData, dao)

    "admin can delete and undelete pages of all types" in {
      val admin = createPasswordAdmin(s"dltr_admn", dao)

      dao.loadPageMeta(discussionId).get.deletedAt mustBe None
      dao.loadPageMeta(forumId).get.deletedAt mustBe None
      dao.loadPageMeta(htmlPageId).get.deletedAt mustBe None

      dao.deletePagesIfAuth(Seq(discussionId, forumId, htmlPageId),
        admin.id, browserIdData, undelete = false)

      dao.loadPageMeta(discussionId).get.deletedAt mustBe defined
      dao.loadPageMeta(forumId).get.deletedAt mustBe defined
      dao.loadPageMeta(htmlPageId).get.deletedAt mustBe defined
      dao.loadPageMeta(otherPageId).get.deletedAt mustBe None

      dao.deletePagesIfAuth(Seq(discussionId, forumId, htmlPageId),
        admin.id, browserIdData, undelete = true)
      dao.loadPageMeta(discussionId).get.deletedAt mustBe None
      dao.loadPageMeta(forumId).get.deletedAt mustBe None
      dao.loadPageMeta(htmlPageId).get.deletedAt mustBe None
    }

    "moderators may delete discussions" in {
      val moderator = createPasswordModerator(s"dltr_mod", dao)

      dao.deletePagesIfAuth(Seq(discussionId), moderator.id, browserIdData, undelete = false)
      dao.loadPageMeta(discussionId).get.deletedAt mustBe defined

      dao.deletePagesIfAuth(Seq(discussionId), moderator.id, browserIdData, undelete = true)
      dao.loadPageMeta(discussionId).get.deletedAt mustBe None

      intercept[ResultException] {
        dao.deletePagesIfAuth(Seq(forumId), moderator.id, browserIdData, undelete = false)
      }.getMessage must include("EsE5GKF23_")

      intercept[ResultException] {
        dao.deletePagesIfAuth(Seq(htmlPageId), moderator.id, browserIdData, undelete = false)
      }.getMessage must include("EsE5GKF23_")
    }

    "do nothing if page doesn't exist" in {
      val admin = createPasswordAdmin(s"dltr_adm2", dao)
      val badPageId = "zzwwffpp"
      dao.deletePagesIfAuth(Seq(badPageId), admin.id, browserIdData, undelete = false)
      dao.loadPageMeta(badPageId) mustBe None
    }

    "non-staff may not delete" in {
      val user = createPasswordUser(s"dltr_mbr", dao)
      val discussionId = createPage(PageRole.Discussion, TextAndHtml.testTitle("Title"),
        TextAndHtml.testBody("Body text"), authorId = SystemUserId, browserIdData, dao)
      intercept[ResultException] {
        dao.deletePagesIfAuth(Seq(discussionId), user.id, browserIdData, undelete = false)
      }.getMessage must include("EsE7YKP424_")
    }
  }

}
