/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */

package debiki

import com.debiki.v0._
import com.twitter.ostrich.stats.Stats
import java.{util => ju, io => jio}
import scala.collection.JavaConversions._
import _root_.scala.xml.{NodeSeq, Node, Elem, Text, XML, Attribute}
import FlagReason.FlagReason
import Prelude._
import DebikiHttp._


abstract class HtmlConfig {
  def termsOfUseUrl: String

  // If a form action is the empty string, the browser POSTS to the current
  // page, says the URI spec: http://www.apps.ietf.org/rfc/rfc3986.html#sec-5.4
  // COULD rename replyAction -> replyUrl (or reactUrl -> reactAction).
  def replyAction = "?reply"
  def rateAction = "?rate"
  def flagAction = "?flag"
  def loginActionSimple = ""
  def loginActionOpenId = ""
  def loginOkAction = ""
  def loginFailedAction = ""
  def logoutAction = ""

  /** A function from debate-id and post-id to a react URL.
   */
  def reactUrl(debateId: String, postId: String) = "?act="+ postId

  /** Constructs a URL to more info on a certain user,
   *  adds "http://" if needed.
   */
  def userUrl(nilo: NiLo) = {
    "" // for now, since OpenID users cannot specify url, fix ...
    /*
    // Lift-Web or Java? escapes cookie values, so unescape `://'.
    // Scala 1.9? val ws = user.website.replaceAllLiterally("%3A%2F%2F", "://")
    val ws = user.website.replaceAll("%3A%2F%2F", "://")
    if (ws isEmpty) {
      ""
    } else if (ws.startsWith("http://") ||
                ws.startsWith("https://")) {
      ws
    } else {
      "http://"+ ws
    } */
  }

  /** Whether or not to show edit suggestions. */
  def showEdits_? = false  // doesn't work at all right now
  def hostAndPort = "localhost"

}


object HtmlSerializer {


  /** Converts text to xml, returns (html, approx-line-count).
   *
   * Splits into <p>:s and <br>:s at newlines, does nothing else.
   */
  def textToHtml(text: String, charsPerLine: Int = 80)
      : Tuple2[NodeSeq, Int] = {
    var lineCount = 0
    val xml =
      // Two newlines end a paragraph.
      for (para <- text.split("\n\n").toList)
      yield {
        // One newline results in a row break.
        val lines = para.split("\n").zipWithIndex.flatMap(lineAndIndex => {
          lineCount += 1 + lineAndIndex._1.length / charsPerLine
          if (lineAndIndex._2 == 0) Text(lineAndIndex._1)
          else <br/> ++ Text(lineAndIndex._1)
        })
        <p>{lines}</p>
      }
    (xml, lineCount)
  }

  def ifThen(condition: Boolean, html: NodeSeq): NodeSeq =
    if (condition) html else Nil


  /**
   * Converts markdown to xml.
   */
  def markdownToSafeHtml(source: String, hostAndPort: String,
        makeLinksNofollow: Boolean, allowClassIdDataAttrs: Boolean): NodeSeq
        = Stats.time("markdownToSafeHtml") {
    val htmlTextUnsafe =
       (new compiledjs.ShowdownJsImpl()).makeHtml(source, hostAndPort)
    sanitizeHtml(htmlTextUnsafe, makeLinksNofollow, allowClassIdDataAttrs)
  }


  def sanitizeHtml(htmlTextUnsafe: String,
        makeLinksNofollow: Boolean, allowClassIdDataAttrs: Boolean): NodeSeq = {

    var htmlTextSafe: String =
      (new compiledjs.HtmlSanitizerJsImpl()).googleCajaSanitizeHtml(
        htmlTextUnsafe,
        // Cannot specify param names, regrettably, since we're calling
        // Java / compiled Javascript code.
        allowClassIdDataAttrs, // allowClassAndIdAttr
        allowClassIdDataAttrs) // allowDataAttr

    // As of 2011-08-18 the Google Caja js html sanitizer strips
    // target='_blank', (Seems to be a bug:
    // `Issue 1296: target="_blank" is allowed, but cleared by html_sanitize()'
    // http://code.google.com/p/google-caja/issues/detail?id=1296  )
    // Add target _blank here - not needed any more, I ask "do you really
    // want to close the page?" if people have started writing.
    //   htmlTextSafe = htmlTextSafe.replace("<a ", "<a target='_blank' ")

    if (makeLinksNofollow)
      htmlTextSafe = htmlTextSafe.replace("<a ", "<a rel='nofollow' ")

    // Use a HTML5 parser; html_sanitize outputs HTML5, which Scala's XML
    // parser don't understand (e.g. an <img src=…> tag with no </img>).
    // Lift-Web uses a certain nu.validator HTML5 parser; use it.
    // (html_sanitize, from Google Caja, also uses the very same parser.
    // So html_sanitize probably generates html that Lift-Web's version
    // of the nu.validator parser understands.)
    // Wrap the html text in a dummy tag to avoid a SAXParseException.
    liftweb.Html5.parse("<div>"+ htmlTextSafe +"</div>").get.child
  }


  /** Replaces spaces with the Unicode representation of non-breaking space,
   *  which is interpreted as {@code &nbsp;} by Web browsers.
   */
  def spaceToNbsp(text: String): String = text.replace(' ', '\u00a0')

  def dateAbbr(date: ju.Date, cssClass: String): NodeSeq = {
    val dateStr = toIso8601(date)
    <abbr class={"dw-date "+ cssClass} title={toIso8601T(dateStr)}>, {
      dateStr}</abbr>
  }

  /** Shows a link to the user represented by NiLo.
   */
  def linkTo(nilo: NiLo, config: HtmlConfig): NodeSeq = {
    var url = config.userUrl(nilo)
    // TODO: investigate: `url' is sometimes the email address!!
    // When signed in @gmail.com, it seems.
    // For now: (this is actually a good test anyway, in case someone
    // accidentally enters his email in the website field?)
    if (url.contains('@') || url.containsSlice("%40")) {
      System.err.println(
        "URL contains email? It contains `@' or `%40': "+ url)
      url = ""
    }
    val nameElem: NodeSeq = nilo.identity_! match {
      case s: IdentitySimple =>
        // Indicate that the user was not logged in, that we're not sure
        // about his/her identity, by appending "??". If however s/he
        // provided an email address then only append one "?", because
        // other people probaably don't know what is it, so it's harder
        // for them people to impersonate her.
        // (Well, at least if I some day in some way indicate that two
        // persons with the same name actually have different emails.)
        xml.Text(nilo.displayName) ++
            <span class='dw-lg-t-spl'>{
              if (s.email isEmpty) "??" else "?"}</span>
      case _ => xml.Text(nilo.displayName)
    }
    val userLink = if (url nonEmpty) {
      <a class='dw-p-by' href={url} data-dw-u-id={nilo.user_!.id}
         rel='nofollow' target='_blank'>{nameElem}</a>
    } else {
      <span class='dw-p-by' data-dw-u-id={nilo.user_!.id}>{nameElem}</span>
    }
    userLink
  }

  /** XML for the user name and login/out links.
   */
  def loginInfo(userName: Option[String]): NodeSeq = {
    // COULD remove the "&nbsp;&nbsp;|&nbsp;&nbsp;" (below) from the link,
    // but then it'd always be visible, as of right now.
    <span id='dw-u-info'>
      <span class='dw-u-name'>{userName.getOrElse("")}</span>
    </span>
    <span class='dw-u-lgi-lgo'>
      <a id='dw-a-login'>Logga in</a>
      <a id='dw-a-logout'>Logga ut</a>
    </span>
  }

  /**
   * A script tag that hides comments and shows them on click.
   */
  val tagsThatHideShowInteractions = (
    <script type="text/javascript">
    jQuery('html').addClass('dw-hide-interactions');
    debiki.scriptLoad.done(function() {{
      debiki.v0.showInteractionsOnClick();
    }});
    </script>
  )

  private def _attrEq(name: String, value: String)(node: Node) =
    node.attribute(name).filter(_.text == value).isDefined

  def findChildrenOfNode(withClass: String, in: NodeSeq): Option[NodeSeq] =
    (in \\ "_").filter(_attrEq("class", withClass)).
        headOption.map(_.child)

  case class SerializedSingleThread(
    prevSiblingId: Option[String],
    htmlNodes: NodeSeq)

}


case class HtmlSerializer(
  val debate: Debate,
  val pageTrust: PageTrust,
  val config: HtmlConfig) {

  import HtmlSerializer._

  def page = debate

  private lazy val pageStats = new PageStats(debate, pageTrust)

  private def lastChange: Option[String] =
    debate.lastChangeDate.map(toIso8601(_))


  def serializeSingleThread(postId: String, pageRoot: PageRoot)
        : Option[SerializedSingleThread] = {
    page.vipo(postId) map { post =>
      val html = _layoutComments(pageRoot.subId, depth = post.depth,
         parentReplyBtn = Nil,
         posts = post::Nil)
      val siblingsSorted = _sortPostsDescFitness(post.siblingsAndMe)
      var prevSibling: Option[ViPo] = None
      siblingsSorted.takeWhile(_.id != postId).foreach { sibling =>
        prevSibling = Some(sibling)
      }
      SerializedSingleThread(prevSibling.map(_.id), html)
    }
  }


  /**
   * The results from layoutPosts doesn't depend on who the user is
   * and can thus be cached. If a user has rated comment or has written
   * comments that are pending approval, then info on that stuff is
   * appended at the end of the server's reply (in a special <div>), and
   * client side Javascript update the page with user specific stuff.
   */
  def layoutPage(pageRoot: PageRoot): NodeSeq = Stats.time("layoutPage") {

    val cssArtclThread =
      if (pageRoot.subId == Page.BodyId) " dw-ar-t" else ""
    val rootPostsReplies = pageRoot.findChildrenIn(debate)
    val rootPost: ViPo = pageRoot.findOrCreatePostIn(debate) getOrElse
       throwNotFound("DwE0PJ404", "Post not found: "+ pageRoot.subId)

    val cssThreadId = "dw-t-"+ rootPost.id
    <div id={"page-"+ debate.id} class='debiki dw-debate dw-page'>
      <div class="dw-debate-info">{
        if (lastChange isDefined) {
          <p class="dw-last-changed">Last changed on
          <abbr class="dw-date"
                title={toIso8601T(lastChange.get)}>{lastChange.get}</abbr>
          </p>
        }
      }
      </div>
      <div id={cssThreadId}
           class={"dw-t"+ cssArtclThread +" dw-depth-0 dw-hor"}>
      {
        val renderedComment = _showComment(rootPost.id, rootPost)
        val replyBtn = _replyBtnListItem(renderedComment.replyBtnText)

        renderedComment.html ++
        <div class='dw-t-vspace'/>
        <ol class='dw-res'>{
          _layoutComments(rootPost.id, 1, replyBtn, rootPostsReplies)
        }
        </ol>
      }
      </div>
    </div>
  }


  private def _replyBtnListItem(replyBtnText: NodeSeq): NodeSeq = {
    // Don't show the Rate and Flag buttons. An article-question
    // cannot be rated/flaged separately (instead, you flag the
    // article).
    <li class='dw-hor-a dw-p-as dw-as'>{/* COULD remove! dw-hor-a */}
      <a class='dw-a dw-a-reply'>{replyBtnText}</a>{/*
      COULD remove More... and Edits, later when article questions
      are merged into the root post, so that there's only one Post
      to edit (but >= 1 posts are created when the page is
      rendered) */}
      <a class='dw-a dw-a-more'>More...</a>
      <a class='dw-a dw-a-edit'>Edits</a>
    </li>
  }


  private def _sortPostsDescFitness(posts: List[ViPo]): List[ViPo] = {
    // Sort by: 1) Fixed position, 2) fitness, descending, 3) time, ascending.
    // Could skip sorting inline posts, since sorted by position later
    // anyway, in javascript. But if javascript disabled?
    posts.sortBy(p => p.creationDati.getTime). // the oldest first
       sortBy(p => -pageStats.ratingStatsFor(p.id).
       fitnessDefaultTags.lowerLimit).
       sortBy(p => p.meta.fixedPos.getOrElse(999999))
  }


  private def _layoutComments(rootPostId: String, depth: Int,
                              parentReplyBtn: NodeSeq,
                              posts: List[ViPo]): NodeSeq = {
    // COULD let this function return Nil if posts.isEmpty, and otherwise
    // wrap any posts in <ol>:s, with .dw-ts or .dw-i-ts CSS classes
    // — this would reduce dupl wrapping code.
    var replyBtnPending = parentReplyBtn.nonEmpty

    var comments: NodeSeq = Nil
    for {
      p <- _sortPostsDescFitness(posts)
      cssThreadId = "dw-t-"+ p.id
      cssDepth = "dw-depth-"+ depth
      isInlineThread = p.where.isDefined
      isInlineNonRootChild = isInlineThread && depth >= 2
      cssInlineThread = if (isInlineThread) " dw-i-t" else ""
      replies = p.replies
      vipo = p // debate.vipo_!(p.id)
      isRootOrArtclQstn = vipo.id == rootPostId || vipo.meta.isArticleQuestion
      // Layout replies horizontally, if this is an inline reply to
      // the root post, i.e. depth is 1 -- because then there's unused space
      // to the right. However, the horizontal layout results in a higher
      // thread if there's only one reply. So only do this if there's more
      // than one reply.
      horizontal = (p.where.isDefined && depth == 1 && replies.length > 1) ||
                    isRootOrArtclQstn
      cssThreadDeleted = if (vipo.isTreeDeleted) " dw-t-dl" else ""
      cssArticleQuestion = if (isRootOrArtclQstn) " dw-p-art-qst" else ""
      postFitness = pageStats.ratingStatsFor(p.id).fitnessDefaultTags
      // For now: If with a probability of 90%, most people find this post
      // boring/faulty/off-topic, and if, on average,
      // more than two out of three people think so too, then fold it.
      // Also fold inline threads (except for root post inline replies)
      // -- they confuse people (my father), in their current shape.
      shallFoldPost = (postFitness.upperLimit < 0.5f &&
         postFitness.observedMean < 0.333f) || isInlineNonRootChild
    } {
      val renderedComment: RenderedComment =
        if (vipo.isTreeDeleted) _showDeletedTree(vipo)
        else if (vipo.isDeleted) _showDeletedComment(vipo)
        else _showComment(rootPostId, vipo)

      val myReplyBtn =
        if (!isRootOrArtclQstn) Nil
        else _replyBtnListItem(renderedComment.replyBtnText)

      val (cssFolded, foldLinkText) =
        if (shallFoldPost)
          (" dw-zd", "[+] Click to show more replies" +
             renderedComment.topRatingsText.map(", rated "+ _).getOrElse(""))
        else
          ("", "[–]") // the – is an `em dash' not a minus

      val foldLink =
        if (isRootOrArtclQstn || vipo.isTreeDeleted) Nil
        else <a class='dw-z'>{foldLinkText}</a>

      val repliesHtml =
        if (replies.isEmpty && myReplyBtn.isEmpty) Nil
        // COULD delete only stuff *older* than the tree deletion.
        else if (vipo.isTreeDeleted) Nil
        else <ol class='dw-res'>
          { _layoutComments(rootPostId, depth + 1, myReplyBtn, replies) }
        </ol>

      var thread = {
        val cssHoriz = if (horizontal) " dw-hor" else ""
        <li id={cssThreadId} class={"dw-t "+ cssDepth + cssInlineThread +
               cssFolded + cssHoriz + cssThreadDeleted + cssArticleQuestion}>{
          foldLink ++
          renderedComment.html ++
          repliesHtml
        }</li>
      }

      // For inline comments, add info on where to place them.
      // COULD rename attr to data-where, that's search/replace:able enough.
      if (p.where isDefined) thread = thread % Attribute(
        None, "data-dw-i-t-where", Text(p.where.get), scala.xml.Null)

      // Place the Reply button just after the last fixed-position comment.
      // Then it'll be obvious (?) that if you click the Reply button,
      // your comment will appear to the right of the fixed-pos comments.
      if (replyBtnPending && p.meta.fixedPos.isEmpty) {
        replyBtnPending = false
        comments ++= parentReplyBtn
      }
      comments ++= thread
    }

    if (replyBtnPending) comments ++ parentReplyBtn
    else comments
  }

  private def _showDeletedTree(vipo: ViPo): RenderedComment = {
    _showDeletedComment(vipo, wholeTree = true)
  }

  private def _showDeletedComment(vipo: ViPo, wholeTree: Boolean = false
                                     ): RenderedComment = {
    val cssPostId = "post-"+ vipo.id
    val deletion = vipo.firstDelete.get
    val deleter = debate.people.authorOf_!(deletion)
    // COULD add itemscope and itemtype attrs, http://schema.org/Comment
    val html =
    <div id={cssPostId} class='dw-p dw-p-dl'>
      <div class='dw-p-hd'>{
        if (wholeTree) "Thread" else "1 comment"
        } deleted by { _linkTo(deleter)
        /* TODO show flagsTop, e.g. "flagged spam".
        COULD include details, shown on click:
        Posted on ...,, rated ... deleted on ..., reasons for deletion: ...
        X flags: ... -- but perhaps better / easier with a View link,
        that opens the deleted post, incl. details, in a new browser tab?  */}
      </div>
    </div>
    RenderedComment(html, replyBtnText = Nil, topRatingsText = None,
       templCmdNodes = Nil)
  }


  case class RenderedComment(
    html: NodeSeq,
    replyBtnText: NodeSeq,
    topRatingsText: Option[String],
    templCmdNodes: NodeSeq)


  private def _showComment(rootPostId: String, vipo: ViPo): RenderedComment = {
    def post = vipo.post
    val editsApplied: List[ViEd] = vipo.editsAppliedDescTime
    val lastEditApplied = editsApplied.headOption
    val cssPostId = "post-"+ post.id
    val (cssArtclPost, cssArtclBody) =
      if (post.id != Page.BodyId) ("", "")
      else (" dw-ar-p", " dw-ar-p-bd")
    val sourceText = vipo.text
    val isRootOrArtclQstn = vipo.id == rootPostId ||
        vipo.meta.isArticleQuestion
    val isArticle = vipo.id == Page.BodyId

    // COULD move to class Markup?
    // Use nofollow links in people's comments, so Google won't punish
    // the website if someone posts spam.
    val makeNofollowLinks = !isRootOrArtclQstn

    val (xmlTextInclTemplCmds, numLines) = vipo.markup match {
      case "dmd0" =>
        // Debiki flavored markdown.
        val html = markdownToSafeHtml(
           sourceText, config.hostAndPort, makeNofollowLinks,
          allowClassIdDataAttrs = isArticle)
        (html, -1)
      case "para" =>
        textToHtml(sourceText)
      /*
      case "link" =>
        textToHtml(sourceText) and linkify-url:s, w/ rel=nofollow)
      case "img" =>
        // Like "link", but also accept <img> tags and <pre>.
        // But nothing that makes text stand out, e.g. skip <h1>, <section>.
        */
      case "html" =>
        (sanitizeHtml(sourceText, makeNofollowLinks,
           allowClassIdDataAttrs = isArticle), -1)
      case "code" =>
        (<pre class='prettyprint'>{sourceText}</pre>,
          sourceText.count(_ == '\n'))
      /*
      case c if c startsWith "code" =>
        // Wrap the text in:
        //     <pre class="prettyprint"><code class="language-javascript">
        //  That's an HTML5 convention actually:
        // <http://dev.w3.org/html5/spec-author-view/
        //     the-code-element.html#the-code-element>
        // Works with: http://code.google.com/p/google-code-prettify/
        var lang = c.dropWhile(_ != '-')
        if (lang nonEmpty) lang = " lang"+lang  ; UNTESTED // if nonEmpty
        (<pre class={"prettyprint"+ lang}>{sourceText}</pre>,
          sourceText.count(_ == '\n'))
        // COULD include google-code-prettify js and css, and
        // onload="prettyPrint()".
        */
      case _ =>
        // Default to Debiki flavored markdown, for now.
        // (Later: update database, change null/'' to "dmd0" for the page body,
        // change to "para" for everything else.
        // Then warnDbgDie-default to "para" here not "dmd0".)
        (markdownToSafeHtml(sourceText, config.hostAndPort,
           makeNofollowLinks, allowClassIdDataAttrs = isArticle), -1)
    }

    // Find any customized reply button text.
    var replyBtnText: NodeSeq = xml.Text("Reply")
    if (isRootOrArtclQstn) {
      findChildrenOfNode(withClass = "debiki-0-reply-button-text",
         in = xmlTextInclTemplCmds) foreach { replyBtnText = _ }
    }

    // Find any template comands.
    val (templCmdNodes: NodeSeq, xmlText: NodeSeq) =
      (Nil: NodeSeq, xmlTextInclTemplCmds)  // for now, ignore all
      //if (!isRootOrArtclQstn) (Nil, xmlTextInclTemplCmds)
      //else partitionChildsWithDataAttrs(in = xmlTextInclTemplCmds)

    val long = numLines > 9
    val cutS = if (long && post.id != rootPostId) " dw-x-s" else ""
    val author = debate.people.authorOf_!(post)

    val (flagsTop: NodeSeq, flagsDetails: NodeSeq) = {
      if (vipo.flags isEmpty) (Nil: NodeSeq, Nil: NodeSeq)
      else {
        import HtmlForms.FlagForm.prettify
        val mtime = toIso8601T(vipo.lastFlag.get.ctime)
        val fbr = vipo.flagsByReasonSorted
        (<span class='dw-p-flgs-top'>, flagged <em>{
            prettify(fbr.head._1).toLowerCase}</em></span>,
        <div class='dw-p-flgs-all' data-mtime={mtime}>{
          vipo.flags.length} flags: <ol class='dw-flgs'>{
            for ((r: FlagReason, fs: List[Flag]) <- fbr) yield
              <li class="dw-flg">{
                // The `×' is the multiplication sign, "\u00D7".
                prettify(r).toLowerCase +" × "+ fs.length.toString
              } </li>
          }</ol>
        </div>)
      }
    }

    val postRatingStats = pageStats.ratingStatsFor(post.id)
    // Sort the rating tags by their observed fittingness, descending
    // (the most popular tags first).
    val tagStatsSorted = postRatingStats.tagStats.toList.sortBy(
        -_._2.fitness.observedMean)
    val topTags = if (tagStatsSorted isEmpty) Nil else {
      // If there're any really popular tags ([the lower confidence limit on
      // the probability that they're used] is > 0.4),
      // show all those. Otherwise, show only the most popular tag(s).
      // (Oops, need not be `max' -- they're sorted by the *measured* prob,
      // not the lower conf limit -- well, hardly matters.)
      val maxLowerConfLimit = tagStatsSorted.head._2.fitness.lowerLimit
      val minLower = math.min(0.4, maxLowerConfLimit)
      tagStatsSorted.takeWhile(_._2.fitness.lowerLimit >= minLower)
    }

    val topTagsAsText: Option[String] = {
      def showRating(tagAndStats: Pair[String, TagStats]): String = {
        val tagName = tagAndStats._1
        val tagFitness = tagAndStats._2.fitness
        // A rating tag like "important!!" means "really important", many
        // people agree. And "important?" means "perhaps somewhat important",
        // some people agree.
        // COULD reduce font-size of ? to 85%, it's too conspicuous.
        val mark =
          if (tagFitness.lowerLimit > 0.9) "!!"
          else if (tagFitness.lowerLimit > 0.7) "!"
          else if (tagFitness.lowerLimit > 0.3) ""
          else "?"
        tagName + mark
        // COULD reduce font size of mark to 85%, or it clutters the ratings.
      }
      if (topTags isEmpty) None
      else Some(topTags.take(3).map(showRating(_)).mkString(", "))
    }

    val (ratingTagsTop: NodeSeq, ratingTagsDetails: NodeSeq) = {
      val rats = tagStatsSorted
      if (rats.isEmpty) (Nil: NodeSeq, Nil: NodeSeq)
      else {
        // List popular rating tags. Then all tags and their usage percents,
        // but those details are shown only if one clicks the post header.
        val topTagsAsHtml =
          if (topTagsAsText isEmpty) Nil
          else <span class='dw-p-r dw-p-r-top'>, rated <em>{
            topTagsAsText.get}</em></span>

        val tagDetails = <div class='dw-p-r-all'
             data-mtime={toIso8601T(postRatingStats.lastRatingDate)}>{
          postRatingStats.ratingCountUntrusty} ratings:
          <ol class='dw-p-r dw-rs'>{
          // Don't change whitespace, or `editInfo' perhaps won't
          // be able to append a ',' with no whitespace in front.
          for ((tagName: String, tagStats: TagStats) <- rats) yield
          <li class="dw-r" data-stats={
              ("lo: %.0f" format (100 * tagStats.fitness.lowerLimit)) +"%, "+
              "sum: "+ tagStats.countWeighted}> {
            tagName +" %.0f" format (
               100 * tagStats.fitness.observedMean)}% </li>
        }</ol></div>

        (topTagsAsHtml, tagDetails)
      }
    }

    val editInfo =
      // If closed: <span class='dw-p-re-cnt'>{count} replies</span>
      if (editsApplied.isEmpty) Nil
      else {
        val lastEditDate = vipo.modificationDati
        // ((This identity count doesn't take into account that a user
        // can have many identities, e.g. Twitter, Facebook and Gmail. So
        // even if many different *identities* have edited the post,
        // perhaps only one single actual *user* has edited it. Cannot easily
        // compare users though, because IdentitySimple maps to no user!))
        val editorsCount =
          editsApplied.map(edAp => debate.vied_!(edAp.id).identity_!.id).
          distinct.length
        lazy val editor =
          debate.people.authorOf_!(debate.editsById(lastEditApplied.get.id))
        <span class='dw-p-hd-e'>{
            Text(", edited ") ++
            (if (editorsCount > 1) {
              Text("by ") ++ <a>various people</a>
            } else if (editor.identity_!.id != author.identity_!.id) {
              Text("by ") ++ _linkTo(editor)
            } else {
              // Edited by the author. Don't repeat his/her name.
              Nil
            })
          }{dateAbbr(lastEditDate, "dw-p-at")}
        </span>
      }

    // Make a title for this post.
    val postTitleXml: NodeSeq = {
      // Currently only the page body can have a title.
      if (post.id != Page.BodyId) Nil
      else debate.titlePost match {
        case Some(titlePost) if titlePost.text.nonEmpty =>
          // The title is a post, itself.
          // Therefore this XML is almost identical to the XML
          // for the post that this title entitles.
          // In the future, I could make a recursive call to
          // _renderPost, to render the title. Then it would be
          // possible to reply-inline to the title.
          // (Don't wrap the <h1> in a <header>; there's no need to wrap single
          // tags in a <header>.)
          <div id={"post-"+ titlePost.id} class='dw-p dw-p-ttl'>
            <div class='dw-p-bd'>
              <div class='dw-p-bd-blk'>
                <h1 class='dw-p-ttl'>{titlePost.text}</h1>
              </div>
            </div>
          </div>
        case _ => Nil
      }
    }

    val commentHtml =
    <div id={cssPostId} class={"dw-p" + cssArtclPost + cutS}>
      { postTitleXml }
      <div class='dw-p-hd'>
        By { _linkTo(author)}{ dateAbbr(post.ctime, "dw-p-at")
        }{ flagsTop }{ ratingTagsTop }{ editInfo }{ flagsDetails
        }{ ratingTagsDetails }
      </div>
      <div class={"dw-p-bd"+ cssArtclBody}>
        <div class='dw-p-bd-blk'>
        { xmlText
        // (Don't  place a .dw-i-ts here. Splitting the -bd into
        // -bd-blks and -i-ts is better done client side, where the
        // heights of stuff is known.)
        }
        </div>
      </div>
    </div> ++ (
      if (isRootOrArtclQstn) Nil
      else <a class='dw-as' href={config.reactUrl(debate.guid, post.id) +
                  "&view="+ rootPostId}>React</a>)

    RenderedComment(html = commentHtml, replyBtnText = replyBtnText,
       topRatingsText = topTagsAsText, templCmdNodes = templCmdNodes)
  }

  def _linkTo(nilo: NiLo) = linkTo(nilo, config)
}



object UserHtml {

  def renderInbox(notfs: Seq[NotfOfPageAction]): NodeSeq = {
    if (notfs.isEmpty)
      return  <div class='dw-ibx'><div class='dw-ibx-ttl'/></div>;

    <div class='dw-ibx'>
      <div class='dw-ibx-ttl'>Your inbox:</div>
      <ol> {
        for (notf <- notfs.take(20)) yield {
          val pageAddr = "/-"+ notf.pageId
          val postAddr = pageAddr +"#post-"+ notf.recipientActionId

          notf.eventType match {
            case NotfOfPageAction.Type.PersonalReply =>
              // COULD look up address in PATHS table when loading
              // InboxItem from database -- to get rid of 1 unnecessary redirect.
              <li><a href={postAddr}>1 reply on {notf.pageTitle}</a>,
                by <em>{notf.eventUserDispName}</em>
              </li>

            case _ =>
              // I won't forget to fix this later, when I add more notf types.
              <li><a href={postAddr}>1 something on {notf.pageTitle}</a>,
                by <em>{notf.eventUserDispName}</em>
              </li>
          }
        }
      }
      </ol>
    </div>
  }
}



object AtomFeedXml {

  /**
   * See http://www.atomenabled.org/developers/syndication/.
   * Include in HTML e.g. like so:
   *   <link href="path/to/atom.xml" type="application/atom+xml"
   *        rel="alternate" title="Sitewide ATOM Feed" />
   *
   * feedId: Identifies the feed using a universally unique and
   * permanent URI. If you have a long-term, renewable lease on your
   * Internet domain name, then you can feel free to use your website's
   * address.
   * feedTitle: a human readable title for the feed. Often the same as
   * the title of the associated website.
   * feedMtime: Indicates the last time the feed was modified
   * in a significant way.
   */
  def renderFeed(hostUrl: String, feedId: String, feedTitle: String,
                 feedUpdated: ju.Date, pathsAndPages: Seq[(PagePath, Debate)]
                    ): Node = {
    // Based on:
    //   http://exploring.liftweb.net/master/index-15.html#toc-Section-15.7

    if (!hostUrl.startsWith("http"))
      warnDbgDie("Bad host URL: "+ safed(hostUrl))

    val baseUrl = hostUrl +"/"
    def urlTo(pp: PagePath) = baseUrl + pp.path.dropWhile(_ == '/')

    def pageToAtom(pathAndPage: (PagePath, Debate)): NodeSeq = {
      val pagePath = pathAndPage._1
      val page = pathAndPage._2
      val pageBody = page.body.getOrElse {
        warnDbgDie("Page "+ safed(page.guid) +
              " lacks a root post [error DwE09k14p2]")
        return Nil
      }
      val pageTitle = page.titleText getOrElse pagePath.slugOrIdOrQustnMark
      val pageBodyAuthor =
            pageBody.user.map(_.displayName) getOrElse "(Author name unknown)"
      val hostAndPort = hostUrl.stripPrefix("https://").stripPrefix("http://")
      val urlToPage =  urlTo(pagePath)

      // This takes rather long and should be cached.
      // Use the same cache for both plain HTML pages and Atom and RSS feeds?
      val rootPostHtml =
        HtmlSerializer.markdownToSafeHtml(pageBody.text, hostAndPort,
           makeLinksNofollow = true, // for now
           // No point in including id and class attrs in an atom html feed?
           // No stylesheet or Javascript included that cares about them anyway?
           allowClassIdDataAttrs = false)

      <entry>{
        /* Identifies the entry using a universally unique and
        permanent URI. */}
        <id>{urlToPage}</id>{
        /* Contains a human readable title for the entry. */}
        <title>{pageTitle}</title>{
        /* Indicates the last time the entry was modified in a
        significant way. This value need not change after a typo is
        fixed, only after a substantial modification.
        COULD introduce a page's updatedTime?
        */}
        <updated>{toIso8601T(pageBody.creationDati)}</updated>{
        /* Names one author of the entry. An entry may have multiple
        authors. An entry must [sometimes] contain at least one author
        element [...] More info here:
          http://www.atomenabled.org/developers/syndication/
                                                #recommendedEntryElements  */}
        <author><name>{pageBodyAuthor}</name></author>{
        /* The time of the initial creation or first availability
        of the entry.  -- but that shouldn't be the ctime, the page
        shouldn't be published at creation.
        COULD indroduce a page's publishedTime? publishing time?
        <published>{toIso8601T(ctime)}</published> */
        /* Identifies a related Web page. */}
        <link rel="alternate" href={urlToPage}/>{
        /* Contains or links to the complete content of the entry. */}
        <content type="xhtml">
          <div xmlns="http://www.w3.org/1999/xhtml">
            { rootPostHtml }
          </div>
        </content>
      </entry>
    }

     // Could add:
     // <link>: Identifies a related Web page
     // <author>: Names one author of the feed. A feed may have multiple
     // author elements. A feed must contain at least one author
     // element unless all of the entry elements contain at least one
     // author element.
    <feed xmlns="http://www.w3.org/2005/Atom">
      <title>{feedTitle}</title>
      <id>{feedId}</id>
      <updated>{toIso8601T(feedUpdated)}</updated>
      { pathsAndPages.flatMap(pageToAtom) }
    </feed>
  }
}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list
