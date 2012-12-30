/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */

package debiki

import com.debiki.v0._
import debiki._
import debiki.DebikiHttp._
import java.{util => ju}
import Prelude._


case class AssetBundleAndDependencies(
  assetBundleText: String,
  version: String, // SHA1 hash of assetBundleText
  assetPageIds: Seq[SitePageId],
  configPageId: String)
  // COULD: missingAssets: Seq[MissingAsset])


/* COULD rebuild a broken bundle, if a missing asset is created.
 * siteOrHostId: Either the tenant ID was resolved, or only the address to
 * a not-yet-created website is known.
 * assetPath: Remember page path, not id, since there is not yet any id.
object AssetBundleAndDependencies {
  case class MissingAsset(
    siteHostOrId: Either[String, String],
    assetPath: String)
} */



/**
 * Loads bundles of styles and scripts.
 *
 * If you cache the result, you might want to consider race conditions
 * — have a look at CachingAssetBundleDao.loadBundleAndDependencies().
 */
object AssetBundleLoader {

  /**
   * Loads and concatenates the contents of the files in an asset bundle.
   */
  def loadAssetBundle(
        bundleNameNoSuffix: String,  bundleSuffix: String, dao: TenantDao)
        : AssetBundleAndDependencies = {

    def bundleName = s"$bundleNameNoSuffix.$bundleSuffix"
    def die(exception: DebikiException) =
      throw DebikiException(
        "DwE9b3HK1", o"""Cannot serve '$bundleNameNoSuffix.<version>.$bundleSuffix':
            ${exception.getMessage}""")

    val assetPaths =
      try { findAssetPagePaths(bundleName, dao) }
      catch {
        case ex: DebikiException => die(ex)
      }

    val assetPathsAndPages: Seq[(PagePath, Option[Debate])] = assetPaths map { path =>
      val page = dao.loadPageAnyTenant(
        tenantId = path.tenantId, pageId = path.pageId.get)
      (path, page)
    }

    // Fail unless we found all assets to bundle.
    assetPathsAndPages find (_._2 isEmpty) match {
      case Some((pagePath, None)) =>
        die(DebikiException("DwE53X03", o"""Asset '${pagePath.path}'
          in bundle '$bundleName' not found"""))
      case _ => ()
    }

    val assetBodies: Seq[String] =
      assetPathsAndPages map (_._2.get.bodyText getOrElse "")
    val bundleText = {
      val sb = new StringBuilder
      assetBodies.foreach { sb append _ }
      sb.toString
    }

    val assetPageIds = assetPaths map (_.sitePageId getOrDie "DwE90If5")
    val siteConfigPageId = dao.loadWebsiteConfigPageId() getOrElse
      die(DebikiException("DwE38bkF5", "Website config page was just deleted"))

    val sha1sum = hashSha1Base64UrlSafe(bundleText)

    AssetBundleAndDependencies(
      bundleText, sha1sum, assetPageIds, configPageId = siteConfigPageId)
  }


  private def findAssetPagePaths(
      bundleName: String, dao: TenantDao): Seq[PagePath] = {

    def die(errCode: String, details: String) =
      throw DebikiException(
        errCode, s"There's an error in _website-config.yaml: $details")

    val assetUrls =
      try { dao.loadWebsiteConfig().listAssetBundleUrls(bundleName) }
      catch {
        case ex: WebsiteConfigException =>
          die("DwE2B1x8", ex.getMessage)
      }

    val basePath = PagePath(tenantId = dao.tenantId,
      folder = "/themes/local/", pageId = None, showId = false, pageSlug = "")

    def itsEntryPrefix = s"The 'asset-bundles' entry for '$bundleName'"

    val assetPaths: Seq[PagePath] = assetUrls map { assetUrl =>
      import UrlToPagePathResolver.Result
      UrlToPagePathResolver.resolveUrl(assetUrl, basePath, dao) match {
        case Result.BadUrl(errorMessage) =>
          die("DwE3bK31", o"""$itsEntryPrefix lists an invalid URL: $assetUrl,
             error: $errorMessage""")
        case Result.HostNotFound(host) =>
          die("DwE58BK3", s"$itsEntryPrefix refers to an unknown host: $host")
        case Result.PageNotFound =>
          die("DwE4YBz3", s"$itsEntryPrefix refers to a non-existing page: $assetUrl")
        case Result.Ok(path) =>
          path
      }
    }

    assetPaths
  }

}
