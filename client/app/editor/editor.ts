/*
 * Copyright (c) 2015-2016 Kaj Magnus Lindberg
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

/// <reference path="../../typedefs/react/react.d.ts" />
/// <reference path="../../typedefs/modernizr/modernizr.d.ts" />
/// <reference path="../plain-old-javascript.d.ts" />
/// <reference path="../util/stupid-dialog.ts" />
/// <reference path="../utils/react-utils.ts" />
/// <reference path="../utils/PageUnloadAlerter.ts" />
/// <reference path="../model.ts" />
/// <reference path="../Server.ts" />
/// <reference path="commonmark.ts" />
/// <reference path="SelectCategoryDropdown.ts" />
/// <reference path="PageRoleDropdown.ts" />

//------------------------------------------------------------------------------
   module debiki2.editor {
//------------------------------------------------------------------------------

var d = { i: debiki.internal, u: debiki.v0.util };
var r = React.DOM;
var reactCreateFactory = React['createFactory'];
var ReactBootstrap: any = window['ReactBootstrap'];
var Button = reactCreateFactory(ReactBootstrap.Button);
var Input = reactCreateFactory(ReactBootstrap.Input);
var MenuItem = reactCreateFactory(ReactBootstrap.MenuItem);
var Modal = reactCreateFactory(ReactBootstrap.Modal);
var ModalBody = reactCreateFactory(ReactBootstrap.ModalBody);
var ModalFooter = reactCreateFactory(ReactBootstrap.ModalFooter);
var ModalHeader = reactCreateFactory(ReactBootstrap.ModalHeader);
var ModalTitle = reactCreateFactory(ReactBootstrap.ModalTitle);
var PageUnloadAlerter = utils.PageUnloadAlerter;
var FileAPI = null;

var theEditor: any;
var $: any = window['jQuery'];
var WritingSomethingWarningKey = 'WritingSth';
var WritingSomethingWarning = "You were writing something?";

var getErrorDialog = util.makeStupidDialogGetter();


function ensureEditorCreated(success) {
  if (theEditor) {
    success();
  }
  else {
    Server.loadEditorEtceteraScripts().done(() => {
      FileAPI = window['FileAPI'];
      theEditor = ReactDOM.render(Editor({}), utils.makeMountNode());
      success();
    });
  }
}


export function toggleWriteReplyToPost(postId: number, anyPostType?: number) {
  ensureEditorCreated(() => {
    theEditor.toggleWriteReplyToPost(postId, anyPostType);
  });
}


export function openEditorToEditPost(postId: number) {
  ensureEditorCreated(() => {
    theEditor.editPost(postId);
  });
}


export function editNewForumPage(categoryId: number, role: PageRole) {
  ensureEditorCreated(() => {
    theEditor.editNewForumPage(categoryId, role);
  });
}

export function openToWriteMessage(userId: number) {
  ensureEditorCreated(() => {
    theEditor.openToWriteMessage(userId);
  });
}


export var Editor = createComponent({
  mixins: [debiki2.StoreListenerMixin],

  getInitialState: function() {
    return {
      store: debiki2.ReactStore.allData(),
      visible: false,
      text: '',
      draft: '',
      safePreviewHtml: '',
      replyToPostIds: [],
      editingPostId: null,
      editingPostUid: null,
      messageToUserIds: [],
      newForumTopicCategoryId: null,
      newForumPageRole: null,
      guidelines: null,
      backdropOpacity: 0,
      isUploadingFile: false,
      fileUploadProgress: 0,
      uploadFileXhr: null,
    };
  },

  onChange: function() {
    this.setState({ store: debiki2.ReactStore.allData() });
  },

  componentWillMount: function() {
     this.updatePreview = _.debounce(this.updatePreview, 333);
  },

  componentDidMount: function() {
    this.$columns = $('#esPageColumn, #esWatchbarColumn, #dw-sidebar .dw-comments');
    this.startMentionsParser();
    this.makeEditorResizable();
    this.initUploadFileStuff();
    this.perhapsShowGuidelineModal();
    // Don't scroll the main discussion area, when scrolling inside the editor.
    /* Oops this breaks scrolling in the editor and preview.
    $(this.refs.editor).on('scroll touchmove mousewheel', function(event) {
      event.preventDefault();
      event.stopPropagation();
      return false;
    }); */
  },

  componentDidUpdate: function(prevProps, prevState) {
    this.perhapsShowGuidelineModal();
  },

  focusInputFields: function() {
    var elemToFocus = findDOMNode(this.refs.titleInput);
    if (!elemToFocus) {
      elemToFocus = findDOMNode(this.refs.textarea);
    }
    if (elemToFocus) {
      elemToFocus.focus();
    }
  },

  startMentionsParser: function() {
    $(this.refs.textarea).atwho({
      at: "@",
      search_key: 'username',
      tpl: "<li data-value='${atwho-at}${username}'>${username} (${fullName})</li>",
      callbacks: {
        remote_filter: (prefix, callback) => {
          Server.listUsernames(prefix, callback);
        }
      }
    }).on('inserted.atwho', (event, flag, query) => {
      this.onTextEdited(event);
    });
  },

  makeEditorResizable: function() {
    if (d.i.isInEmbeddedEditor) {
      // The iframe is resizable instead.
      return;
    }
    // We also add class 'resizable' a bit below [7UGM27] because sometimes React removes it.
    $(this.refs.editor).resizable({
      direction: ['top'],
      resize: this.makeSpaceAtBottomForEditor,
    });
  },

  makeSpaceAtBottomForEditor: function() {
    this.$columns.css('bottom', $(this.refs.editor).height());
  },

  returnSpaceAtBottomForEditor: function() {
    this.$columns.css('bottom', 0);
  },

  selectAndUploadFile: function() {
    $(this.refs.uploadFileInput).click();
  },

  // We never un-initialize this, instead we reuse the same editor instance always once created.
  initUploadFileStuff: function() {
    if (!this.refs.uploadFileInput)
      return;

    // Some browsers open a dropped file in the current browser tab, if one misses the
    // drop target with a few pixels. Prevent that. See: http://stackoverflow.com/questions/9544977/using-jquery-on-for-drop-events-when-uploading-files-from-the-desktop#comment20423424_9545050
    $(document).on('dragover', event => {
      event.preventDefault();
      event.stopPropagation();
    }).on('drop', event => {
      event.preventDefault();
      event.stopPropagation();
    });

    FileAPI.event.on(document, 'drop', (event: Event) => {
      event.preventDefault();
      if (!this.state.visible) return;
      FileAPI.getDropFiles(event, (files: File[]) => {
        if (files.length > 1) {
          // This'll log a warning server side, I think I want that (want to know how
          // often this happens)
          die("Sorry but currently you can upload only one file at a time [EsM5JYW2]");
        }
        this.uploadFiles(files);
      });
    });

    var inputElem = this.refs.uploadFileInput;
    FileAPI.event.on(inputElem, 'change', (event) => {
      var files = FileAPI.getFiles(event);
      this.uploadFiles(files);
    });
  },

  uploadFiles: function(files: File[]) {
    if (!files.length)
      return;

    dieIf(files.length != 1, 'EsE5GPY82');
    FileAPI.upload({   // a bit dupl code [2UK503]
      url: '/-/upload-public-file',
      headers: { 'X-XSRF-TOKEN': window['$'].cookie('XSRF-TOKEN') },
      files: { file: files },
      // This is per file.
      fileprogress: (event, file, xhr, options) => {
        if (!this.state.isUploadingFile) {
          this.setState({ isUploadingFile: true });
          pagedialogs.getProgressBarDialog().open("Uploading...", () => {
            this.setState({ uploadCancelled: true });
            xhr.abort("Intentionally cancelled [EsM3GU05]");
          });
        }
        else {
          var percent = event.loaded / event.total * 100;
          pagedialogs.getProgressBarDialog().setDonePercent(percent);
        }
      },
      // This is when all files have been uploaded — but we're uploading just one.
      complete: (error, xhr) => {
        pagedialogs.getProgressBarDialog().close();
        this.setState({
          isUploadingFile: false,
          uploadCancelled: false
        });
        if (error) {
          if (!this.state.uploadCancelled) {
            pagedialogs.getServerErrorDialog().open(xhr);
          }
          return;
        }
        var fileUrlPath = JSON.parse(xhr.response);
        dieIf(!_.isString(fileUrlPath), 'DwE06MF22');
        dieIf(!_.isString(this.state.text), 'EsE5FYZ2');
        var file = xhr.files[0];
        var linkHtml = this.makeUploadLink(file, fileUrlPath);
        var perhapsNewline = this.state.text.endsWith('\n') ? '' : '\n';
        this.setState({
          text: this.state.text + perhapsNewline + '\n' +
            // (There's a sanitizer for this — for everything in the editor.)
          "<!-- Uploaded file name:  " + file.name + "  -->\n" +
          linkHtml,
        });
        // Scroll down so people will see the new line we just appended.
        scrollToBottom(this.refs.textarea);
        this.updatePreview(() => {
          // This happens to early, not sure why. So wait for a while.
          setTimeout(() => {
            scrollToBottom(this.refs.preview);
          }, 800);
        });
      },
    });
  },

  cancelUpload: function() {
    if (this.state.uploadFileXhr) {
      this.state.uploadFileXhr.abort();
    }
    else {
      console.warn("Cannot cancel upload: No this.state.uploadFileXhr [DwE8UMW2]");
    }
  },

  showUploadProgress: function(percent) {
    if (percent === 0) {
      pagedialogs.getProgressBarDialog().open("Uploading...", this.cancelUpload);
    }
    else {
      pagedialogs.getProgressBarDialog().setDonePercent(percent);
    }
    this.setState({
      isUploadingFile: true,
      fileUploadProgress: percent,
    });
  },

  hideUploadProgress: function() {
    pagedialogs.getProgressBarDialog().close();
    this.setState({
      uploadFileXhr: null,
      isUploadingFile: false,
      fileUploadProgress: 0,
    });
  },

  makeUploadLink: function(file, url) {
    // The relative path is like '/-/uploads/public/a/b/c...zwq.suffix' = safe,
    // and we got it from the server.
    dieIf(!url.match(/^[0-9a-z/\.-]+$/),
        "Bad image relative path: " + url + " [DwE8PUMW2]");

    var parts = url.split('.');
    var suffix = parts.length > 1 ? _.last(parts) : '';

    // (SVG doesn't work in old browsers, fine. tif doesn't work for me.)
    var isImage = suffix === 'png' || suffix === 'jpg' || suffix === 'gif' ||
        suffix === 'mpo' || suffix === 'bmp' || suffix === 'svg';

    // Only .mp4 is supported by all browsers.
    var isVideo = suffix === 'mp4' || suffix === 'ogg' || suffix === 'webm';

    var link;
    if (isImage) {
      link = '<img src="' + url + '"></img>';
    }
    else if (isVideo) {
      link = '<video width="490" height="400" controls src="' + url + '"></video>';
    }
    else {
      link = '<a href="' + url + '">' + file.name + '</a> (' + prettyBytes(file.size) + ')';
    }
    return link;
  },

  toggleWriteReplyToPost: function(postId: number, anyPostType?: number) {
    if (this.alertBadState('WriteReply'))
      return;
    var postIds = this.state.replyToPostIds;
    var index = postIds.indexOf(postId);
    if (index === -1) {
      postIds.push(postId);
      this.showEditor();
    }
    else {
      postIds.splice(index, 1);
    }
    // Don't change post type from flat to something else.
    var postType = anyPostType;
    if (postIds.length >= 2 && this.state.anyPostType === PostType.Flat) {
      postType = PostType.Flat;
    }
    this.setState({
      anyPostType: postType,
      replyToPostIds: postIds,
      text: this.state.text || this.state.draft || '',
    });
    if (!postIds.length) {
      this.closeEditor();
    }
    var writingWhat = WritingWhat.ReplyToNotOriginalPost;
    if (_.isEqual([BodyId], postIds)) writingWhat = WritingWhat.ReplyToOriginalPost;
    else if (_.isEqual([NoPostId], postIds)) writingWhat = WritingWhat.ChatComment;
    this.loadGuidelines(writingWhat);
  },

  editPost: function(postId: number) {
    if (this.alertBadState())
      return;
    Server.loadCurrentPostText(postId, (text: string, postUid: number, revisionNr: number) => {
      this.showEditor();
      this.setState({
        anyPostType: null,
        editingPostId: postId,
        editingPostUid: postUid,
        editingPostRevisionNr: revisionNr,
        text: text
      });
      this.updatePreview();
    });
  },

  editNewForumPage: function(categoryId: number, role: PageRole) {
    if (this.alertBadState())
      return;
    this.showEditor();
    var text = this.state.text || this.state.draft || '';
    this.setState({
      anyPostType: null,
      newForumTopicCategoryId: categoryId,
      newForumPageRole: role,
      text: text
    });
    this.loadGuidelines(WritingWhat.NewPage, categoryId, role);
    this.updatePreview();
  },

  openToWriteMessage: function(userId: number) {
    if (this.alertBadState())
      return;
    this.showEditor();
    this.setState({
      messageToUserIds: [userId],
      text: '',
    });
    this.loadGuidelines(WritingWhat.NewPage, null, PageRole.Message);
    this.showAndFadeOutBackdrop();
  },

  showAndFadeOutBackdrop: function() {
    this.setState({ backdropOpacity: 0.83 });
    var fadeBackdrop = () => {
      if (!this.isMounted()) return;
      var opacity = this.state.backdropOpacity;
      var nextOpacity = opacity < 0.01 ? 0 : opacity - 0.009;
      this.setState({ backdropOpacity: nextOpacity });
      if (nextOpacity) {
        setTimeout(fadeBackdrop, 16);
      }
    };
    setTimeout(fadeBackdrop, 1400);
  },

  alertBadState: function(wantsToDoWhat = null) {
    if (wantsToDoWhat !== 'WriteReply' && this.state.replyToPostIds.length > 0) {
      alert('Please first finish writing your post');
      return true;
    }
    if (this.state.messageToUserIds.length) {
      alert('Please first finish writing your message');
      return true;
    }
    if (_.isNumber(this.state.editingPostId)) {
      alert('Please first save your current edits');
      // If this is an embedded editor, for an embedded comments page, that page
      // will now have highlighted some reply button to indicate a reply is
      // being written. But that's wrong, clear those marks.
      if (d.i.isInEmbeddedEditor) {
        window.parent.postMessage(
          JSON.stringify(['clearIsReplyingMarks', {}]), '*');
      }
      else {
        d.i.clearIsReplyingMarks();
      }
      return true;
    }
    if (this.state.newForumPageRole) {
      alert("Please first either save or cancel your new forum topic");
      d.i.clearIsReplyingMarks();
      return true;
    }
    return false;
  },

  loadGuidelines: function(writingWhat: WritingWhat, categoryId?: number, pageRole?: PageRole) {
    var theCategoryId = categoryId || ReactStore.allData().categoryId;
    var thePageRole = pageRole || ReactStore.allData().pageRole;
    var currentGuidelines = this.state.guidelines;
    if (currentGuidelines &&
        currentGuidelines.categoryId === theCategoryId &&
        currentGuidelines.pageRole === thePageRole &&
        currentGuidelines.writingWhat === writingWhat)
      return;

    // Currently there are no drafts, only guidelines.
    Server.loadDraftAndGuidelines(writingWhat, theCategoryId, thePageRole, guidelinesSafeHtml => {
      if (!guidelinesSafeHtml) {
        this.setState({ guidelines: null });
        return;
      }
      var guidelinesHash = hashStringToNumber(guidelinesSafeHtml);
      var hiddenGuidelinesHashes = getFromLocalStorage('dwHiddenGuidelinesHashes') || {};
      var isHidden = hiddenGuidelinesHashes[guidelinesHash];
      this.setState({
        guidelines: {
          writingWhat: writingWhat,
          categoryId: theCategoryId,
          pageRole: thePageRole,
          safeHtml: guidelinesSafeHtml,
          hidden: isHidden,
        }
      });
    });
  },

  // Remembers that these guidelines have been hidden, by storing a hash of the text in localStorage.
  // So, if the guidelines get changed, they'll be shown again (good). COULD delete old hashes if
  // we end up storing > 100? hashes?
  hideGuidelines: function() {
    var guidelines = this.state.guidelines;
    guidelines.hidden = true;
    this.setState({
      guidelines: guidelines,
      showGuidelinesInModal: false,
    });
    var hash = hashStringToNumber(guidelines.safeHtml);
    var hiddenGuidelinesHashes = getFromLocalStorage('dwHiddenGuidelinesHashes') || {};
    hiddenGuidelinesHashes[hash] = true;
    putInLocalStorage('dwHiddenGuidelinesHashes', hiddenGuidelinesHashes);
  },

  showGuidelines: function() {
    var guidelines = this.state.guidelines;
    guidelines.hidden = false;
    this.setState({ guidelines: guidelines });
    // Leave hidden on page reload? I.e. don't update localStorage.
  },

  // If we're showing some guidelines, but they're not visible on screen, then show them
  // in a modal dialog instead — guidelines are supposedly fairly important.
  perhapsShowGuidelineModal: function() {
    if (!this.refs.guidelines)
      return;

    // If the guidelines are visible, we don't need no modal.
    var rect = this.refs.guidelines.getBoundingClientRect();
    if (rect.top >= 0)
      return;

    this.setState({ showGuidelinesInModal: true });
  },

  onTitleEdited: function(event) {
    PageUnloadAlerter.addReplaceWarning(WritingSomethingWarningKey, WritingSomethingWarning);
    this.setState({ title: event.target.value });
    this.updatePreview();
  },

  isTitleOk: function() {
    // For now
    var title = this.state.title ? this.state.title.trim() : null;
    if (!title) return false;
    return true;
  },

  onTextEdited: function(event) {
    PageUnloadAlerter.addReplaceWarning(WritingSomethingWarningKey, WritingSomethingWarning);
    var newText = event.target.value;
    this.setState({ text: newText });
    this.updatePreview();
  },

  isTextOk: function() {
    // For now
    var text = this.state.text ? this.state.text.trim() : null;
    if (!text) return false;
    return true;
  },

  updatePreview: function(anyCallback?) {
    if (!this.isMounted())
      return;

    // (COULD verify still edits same post/thing, or not needed?)
    var isEditingBody = this.state.editingPostId === d.i.BodyId;
    var sanitizerOpts = {
      allowClassAndIdAttr: isEditingBody,
      allowDataAttr: isEditingBody
    };
    var htmlText = markdownToSafeHtml(this.state.text, window.location.host, sanitizerOpts);
    this.setState({
      safePreviewHtml: htmlText
    }, anyCallback);
  },

  changeCategory: function(categoryId: CategoryId) {
    this.setState({ newForumTopicCategoryId: categoryId });
  },

  changeNewForumPageRole: function(pageRole: PageRole) {
    this.setState({ newForumPageRole: pageRole });
  },

  onCancelClick: function() {
    this.closeEditor();
  },

  onSaveClick: function() {
    if (this.state.newForumPageRole) {
      this.saveNewForumPage();
    }
    else if (_.isNumber(this.state.editingPostId)) {
      this.saveEdits();
    }
    else if (this.state.messageToUserIds.length) {
      this.sendPrivateMessage();
    }
    else {
      this.saveNewPost();
    }
  },

  saveEdits: function() {
    this.throwIfBadTitleOrText(null, "Please don't delete all text. Write something.");
    Server.saveEdits(this.state.editingPostId, this.state.text, () => {
      this.clearTextAndClose();
    });
  },

  saveNewPost: function() {
    this.throwIfBadTitleOrText(null, "Please write something.");
    Server.saveReply(this.state.replyToPostIds, this.state.text, this.state.anyPostType, () => {
      this.clearTextAndClose();
    });
  },

  saveNewForumPage: function() {
    this.throwIfBadTitleOrText("Please write a topic title.", "Please write something.");
    var data = {
      categoryId: this.state.newForumTopicCategoryId,
      pageRole: this.state.newForumPageRole,
      pageStatus: 'Published',
      pageTitle: this.state.title,
      pageBody: this.state.text
    };
    Server.createPage(data, (newPageId: string) => {
      this.clearTextAndClose();
      window.location.assign('/-' + newPageId);
    });
  },

  sendPrivateMessage: function() {
    this.throwIfBadTitleOrText("Please write a message title.", "Please write a message.");
    var state = this.state;
    Server.sendMessage(state.title, state.text, state.messageToUserIds, (pageId: string) => {
      this.clearTextAndClose();
      window.location.assign('/-' + pageId);
    });
  },

  throwIfBadTitleOrText: function(titleErrorMessage, textErrorMessage) {
    var errors = '';
    if (titleErrorMessage && isBlank(this.state.title)) {
      errors += titleErrorMessage;
      this.setState({ showTitleErrors: true });
    }
    if (textErrorMessage && isBlank(this.state.text)) {
      if (errors) errors += ' ';
      errors += textErrorMessage;
      this.setState({ showTextErrors: true });
    }
    if (errors) {
      getErrorDialog().open({ body: errors });
      throw 'Bad title or text. Not saving this to the server. [EsM7KCW]';
    }
  },

  cycleMaxHorizBack: function() {
    // Cycle from 1) normal to 2) maximized & tiled vertically, to 3) maximized & tiled horizontally
    // and then back to normal.
    this.setState({
      showMaximized: !this.state.showMaximized || !this.state.splitHorizontally,
      splitHorizontally: this.state.showMaximized && !this.state.splitHorizontally,
    });
  },

  togglePreview: function() {
    this.setState({
      showOnlyPreview: !this.state.showOnlyPreview,
      showMinimized: false,
    });
  },

  toggleMinimized: function() {
    var nextShowMini = !this.state.showMinimized;
    this.setState({ showMinimized: nextShowMini });
    if (nextShowMini) {
      // Wait until after new size has taken effect.
      setTimeout(this.makeSpaceAtBottomForEditor);
    }
    // Else: the editor covers 100% anyway.
  },

  showEditor: function() {
    this.makeSpaceAtBottomForEditor();
    this.setState({ visible: true });
    if (d.i.isInEmbeddedEditor) {
      window.parent.postMessage(JSON.stringify(['showEditor', {}]), '*');
    }
    // After rerender, focus the input fields:
    setTimeout(() => {
      if (this.isMounted()) {
        this.focusInputFields();
      }
    }, 1);
  },

  closeEditor: function() {
    PageUnloadAlerter.removeWarning(WritingSomethingWarningKey);
    this.returnSpaceAtBottomForEditor();
    this.setState({
      visible: false,
      replyToPostIds: [],
      editingPostId: null,
      editingPostUid: null,
      messageToUserIds: [],
      newForumTopicCategoryId: null,
      newForumPageRole: null,
      editingPostRevisionNr: null,
      text: '',
      title: '',
      showTitleErrors: false,
      showTextErrors: false,
      draft: _.isNumber(this.state.editingPostId) ? '' : this.state.text,
      safePreviewHtml: '',
      guidelines: null,
      backdropOpacity: 0,
    });
    // Remove any is-replying highlights.
    if (d.i.isInEmbeddedEditor) {
      window.parent.postMessage(JSON.stringify(['hideEditor', {}]), '*');
    }
    else {
      // (Old jQuery code.)
      $('.dw-replying').removeClass('dw-replying');
    }
  },

  clearTextAndClose: function() {
    this.setState({ text: null, draft: null });
    this.closeEditor();
  },

  showEditHistory: function() {
    dieIf(!this.state.editingPostId || !this.state.editingPostUid, 'EdE5UGMY2');
    debiki2.edithistory.getEditHistoryDialog().open(this.state.editingPostUid);
  },

  makeTextBold: function() {
    var newText = wrapSelectedText(this.refs.textarea, "bold text", '**');
    this.setState({ text: newText });
    this.updatePreview();
  },

  makeTextItalic: function() {
    var newText = wrapSelectedText(this.refs.textarea, "emphasized text", '*');
    this.setState({ text: newText });
    this.updatePreview();
  },

  markupAsCode: function() {
    var newText = wrapSelectedText(this.refs.textarea, "preformatted text", '`');
    this.setState({ text: newText });
    this.updatePreview();
  },

  quoteText: function() {
    var newText = wrapSelectedText(this.refs.textarea, "quoted text", '> ', null, '\n\n');
    this.setState({ text: newText });
    this.updatePreview();
  },

  addHeading: function() {
    var newText = wrapSelectedText(this.refs.textarea, "Heading", '### ', null, '\n\n');
    this.setState({ text: newText });
    this.updatePreview();
  },

  render: function() {
    var state = this.state;
    var store: Store = state.store;
    var me: Myself = store.me;

    var guidelines = state.guidelines;
    var guidelinesElem;
    var showGuidelinesBtn;
    if (guidelines && guidelines.safeHtml) {
      if (guidelines.hidden) {
        showGuidelinesBtn =
          r.a({ className: 'icon-info-circled', onClick: this.showGuidelines });
      }
      else if (this.state.showGuidelinesInModal) {
        // Skip the post-it style guidelines just below.
      }
      else {
        guidelinesElem =
          r.div({ className: 'dw-editor-guidelines-wrap', ref: 'guidelines' },
            r.div({ className: 'dw-editor-guidelines clearfix' },
              r.div({ className: 'dw-editor-guidelines-text',
                dangerouslySetInnerHTML: { __html: this.state.guidelines.safeHtml }}),
              r.a({ className: 'icon-cancel dw-hide', onClick: this.hideGuidelines }, "Hide")));
      }
    }

    var guidelinesModal = GuidelinesModal({ guidelines: guidelines,
        isOpen: this.state.showGuidelinesInModal, close: this.hideGuidelines });

    // Sometimes it's hard to notice that the editor opens. But by making everything very dark,
    // except for the editor, people will see it for sure. We'll make everything dark only for
    // a short while.
    var anyBackdrop = this.state.backdropOpacity < 0.01 ? null :
        r.div({ className: 'esEdtr_backdrop', style: { opacity: this.state.backdropOpacity }});

    var titleInput;
    var pageRoleDropdown;
    var categoriesDropdown;
    if (this.state.newForumPageRole || this.state.messageToUserIds.length) {
      var titleErrorClass = this.state.showTitleErrors && !this.isTitleOk() ? ' esError' : '';
      titleInput =
          r.input({ className: 'title-input esEdtr_titleEtc_title form-control' + titleErrorClass,
              type: 'text', ref: 'titleInput', tabIndex: 1, onChange: this.onTitleEdited,
              placeholder: "Type a title — what is this about, in one brief sentence?" });

      if (this.state.newForumPageRole)
        categoriesDropdown =
          SelectCategoryDropdown({ className: 'esEdtr_titleEtc_category', store: store,
              selectedCategoryId: this.state.newForumTopicCategoryId,
              onCategorySelected: this.changeCategory });

      if (isStaff(me)) {
        pageRoleDropdown = PageRoleDropdown({ store: store, pageRole: this.state.newForumPageRole,
            complicated: store.settings.showComplicatedStuff,
            onSelect: this.changeNewForumPageRole,
            title: 'Page type', className: 'esEdtr_titleEtc_pageRole' });
      }
    }

    var editingPostId = this.state.editingPostId;
    var replyToPostIds = this.state.replyToPostIds;
    var isChatComment = replyToPostIds.length === 1 && replyToPostIds[0] === NoPostId;
    var isChatReply = replyToPostIds.indexOf(NoPostId) !== -1 && !isChatComment;

    var doingWhatInfo;
    if (_.isNumber(editingPostId)) {
      doingWhatInfo =
        r.span({},
          'Edit ', r.a({ href: '#post-' + editingPostId }, 'post ' + editingPostId + ':'));
    }
    else if (this.state.messageToUserIds.length) {
      doingWhatInfo = "Your message:";
    }
    else if (this.state.newForumPageRole) {
      var what = "Create new topic";
      switch (this.state.newForumPageRole) {
        case PageRole.CustomHtmlPage: what = "Create a custom HTML page (add your own <h1> title)"; break;
        case PageRole.WebPage: what = "Create an info page"; break;
        case PageRole.Code: what = "Create a source code page"; break;
        case PageRole.SpecialContent: die('DwE5KPVW2'); break;
        case PageRole.EmbeddedComments: die('DwE2WCCP8'); break;
        case PageRole.Blog: die('DwE2WQB9'); break;
        case PageRole.Forum: die('DwE5JKF9'); break;
        case PageRole.About: die('DwE1WTFW8'); break;
        case PageRole.Question: what = "Ask a question"; break;
        case PageRole.Problem: what = "Report a problem"; break;
        case PageRole.Idea: what = "Suggest an idea"; break;
        case PageRole.ToDo: what = "Create a todo"; break;
        case PageRole.MindMap: what = "Create a mind map page"; break;
        case PageRole.Discussion: break; // use default
        case PageRole.Message: die('EsE2KFE78'); break;
        case PageRole.Critique: what = "Ask for critique"; break; // [plugin]
      }
      doingWhatInfo = what + ":";
    }
    else if (replyToPostIds.length === 0) {
      doingWhatInfo = 'Please select one or more posts to reply to.';
    }
    else if (isChatComment) {
      doingWhatInfo = "New chat comment:";
    }
    else if (_.isEqual([BodyId], replyToPostIds) && isCritiquePage()) { // [plugin]
      doingWhatInfo = "Your critique:";
    }
    else if (replyToPostIds.length > 0) {
      doingWhatInfo =
        r.span({},
          isChatReply ? 'Chat reply to ' : 'Reply to ',
          _.filter(replyToPostIds, (id) => id !== NoPostId).map((postId, index) => {
            var anyAnd = index > 0 ? ' and ' : '';
            var whichPost = postId === 1 ? 'the Original Post' : 'post ' + postId;
            return (
              r.span({ key: postId },
                anyAnd,
                r.a({ href: '#post-' + postId }, whichPost)));
          }),
          ':');
    }

    var saveButtonTitle = 'Save';
    if (_.isNumber(this.state.editingPostId)) {
      saveButtonTitle = 'Save edits';
    }
    else if (replyToPostIds.length) {
      if (isChatComment) {
        saveButtonTitle = "Post comment";
      }
      else {
        saveButtonTitle = "Post reply";
        if (_.isEqual([BodyId], replyToPostIds) && isCritiquePage()) { // [plugin]
          saveButtonTitle = "Submit critique";
        }
      }
    }
    else if (this.state.messageToUserIds.length) {
      saveButtonTitle = "Send message";
    }
    else if (this.state.newForumPageRole) {
      saveButtonTitle = 'Create topic';
    }

    var anyViewHistoryButton;
    if (this.state.editingPostRevisionNr && this.state.editingPostRevisionNr !== 1) {
      anyViewHistoryButton =
          r.a({ onClick: this.showEditHistory, className: 'view-edit-history', tabIndex: 1 },
            "View old edits");
    }

    // If not visible, don't remove the editor, just hide it, so we won't have
    // to unrigister the mentions parser (that would be boring).
    var styles = {
      display: this.state.visible ? 'block' : 'none'
    };

    // Make space for the soft keyboard on touch devices.
    var maxHeightCss = !Modernizr.touchevents || debiki2.utils.isMouseDetected ? undefined : {
      maxHeight: screen.height / 2.5
    };

    var anyTextareaInstructions;
    if (this.state.newForumPageRole === PageRole.Critique) {  // [plugin]
      anyTextareaInstructions =
          r.div({ className: 'editor-instructions' },
              "Add a link to your work, or upload an image. " +
              "And tell people what you want feedback about:");
    }

    var textareaButtons =
      r.div({ className: 'esEdtr_txtBtns' },
        r.button({ onClick: this.selectAndUploadFile, title: "Upload a file or image",
            className: 'esEdtr_txtBtn' },
          r.span({ className: 'icon-upload' })),
        r.input({ name: 'files', type: 'file', multiple: false, // dupl code [2UK503]
          ref: 'uploadFileInput', style: { width: 0, height: 0, float: 'left' }}),
        r.button({ onClick: this.makeTextBold, title: "Make text bold",
            className: 'esEdtr_txtBtn' }, 'B'),
        r.button({ onClick: this.makeTextItalic, title: "Emphasize",
          className: 'esEdtr_txtBtn esEdtr_txtBtn-em' }, r.i({}, 'I')),
        r.button({ onClick: this.quoteText, title: "Quote",
          className: 'esEdtr_txtBtn' }, '"'),
        r.button({ onClick: this.markupAsCode, title: "Preformatted text",
          className: 'esEdtr_txtBtn' }, r.span({ className: 'icon-code' })),
        r.button({ onClick: this.addHeading, title: "Heading",
            className: 'esEdtr_txtBtn' }, 'H'));

    var textErrorClass = this.state.showTextErrors && !this.isTextOk() ? ' esError' : '';
    var textarea =
        r.textarea({ className: 'editor form-control esEdtr_textarea' +  textErrorClass,
            ref: 'textarea', value: this.state.text,
            onChange: this.onTextEdited, tabIndex: 1,
            placeholder: "Type here. You can use Markdown and HTML. " +
                "Drag and drop to paste images." });

    var previewHelp =
        r.div({ className: 'dw-preview-help' },
          help.HelpMessageBox({ message: previewHelpMessage }));

    // (The $.resizable plugin needs class 'resizable' here. [7UGM27])
    var editorClasses = d.i.isInEmbeddedEditor ? '' : 'editor-box-shadow resizable';
    editorClasses += this.state.showMinimized ? ' editor-minimized' : '';
    editorClasses += this.state.showMaximized ? ' editor-maximized' : '';
    editorClasses += this.state.splitHorizontally ? ' editor-split-hz' : '';

    var editorStyles = this.state.showOnlyPreview ? { display: 'none' } : null;
    var previewStyles = this.state.showOnlyPreview ? { display: 'block' } : null;

    var maximizeAndHorizSplitBtnTitle =
        !this.state.showMaximized ? "Maximize" : (
          this.state.splitHorizontally ? "Back to normal" : "Tile horizontally");

    return (
      r.div({ style: styles },
        guidelinesModal,
        anyBackdrop,
        r.div({ id: 'debiki-editor-controller', ref: 'editor', style: maxHeightCss,
            className: editorClasses },
          guidelinesElem,
          r.div({ id: 'editor-after-borders' },
            r.div({ className: 'editor-area', style: editorStyles },
              r.div({ className: 'editor-area-after-borders' },
                r.div({ className: 'dw-doing-what' },
                  doingWhatInfo, showGuidelinesBtn),
                r.div({ className: 'esEdtr_titleEtc' },
                  // COULD use https://github.com/marcj/css-element-queries here so that
                  // this will wrap to many lines also when screen wide but the editor is narrow.
                  titleInput,
                  categoriesDropdown,
                  pageRoleDropdown),
                anyTextareaInstructions,
                textareaButtons,
                textarea)),
            r.div({ className: 'preview-area', style: previewStyles },
              r.div({}, titleInput ? 'Preview: (title excluded)' : 'Preview:'),
              previewHelp,
              r.div({ className: 'preview', ref: 'preview',
                  dangerouslySetInnerHTML: { __html: this.state.safePreviewHtml }})),
            r.div({ className: 'submit-cancel-btns' },
              Button({ onClick: this.onSaveClick, bsStyle: 'primary', tabIndex: 1,
                  className: 'e2eSaveBtn' },
                saveButtonTitle),
              Button({ onClick: this.onCancelClick, tabIndex: 1,
                  className: 'e2eCancelBtn' }, "Cancel"),
              Button({ onClick: this.cycleMaxHorizBack, className: 'esEdtr_cycleMaxHzBtn',
                  tabIndex: 4 }, maximizeAndHorizSplitBtnTitle),
              // These two buttons are hidden via CSS if the window is wide. Higher tabIndex
              // because float right.
              Button({ onClick: this.toggleMinimized, id: 'esMinimizeBtn',
                  bsStyle: (this.state.showMinimized ? 'primary' : undefined), tabIndex: 3 },
                this.state.showMinimized ? 'Show editor again' : 'Minimize'),
              Button({ onClick: this.togglePreview, id: 'esPreviewBtn', tabIndex: 2 },
                this.state.showOnlyPreview ? 'Edit' : 'Preview'),
              anyViewHistoryButton)))));
  }
});


var GuidelinesModal = createClassAndFactory({
  render: function () {
    var body = !this.props.isOpen ? null :
      r.div({ className: 'dw-editor-guidelines-text',
        dangerouslySetInnerHTML: { __html: this.props.guidelines.safeHtml }});
    return (
      Modal({ show: this.props.isOpen, onHide: this.props.close,
          dialogClassName: 'es-guidelines-modal' },
        // ModalHeader({}, ModalTitle({}, "Guidelines")),
        ModalBody({}, body),
        ModalFooter({}, Button({ onClick: this.props.close }, "Okay"))));
  }
});


function isCritiquePage(): boolean {  // [plugin]
  return ReactStore.allData().pageRole === PageRole.Critique;
}


var SelectCategoryInput = createClassAndFactory({
  render: function () {
    var categoryOptions = this.props.categories.map((category: Category) => {
      return r.option({ value: category.id, key: category.id }, category.name);
    });

    return (
      Input({
          type: 'select', label: this.props.label, title: this.props.title,
          labelClassName: this.props.labelClassName,
          wrapperClassName: this.props.wrapperClassName,
          value: this.props.categoryId, onChange: this.props.onChange },
        categoryOptions));
  }
});


function wrapSelectedText(textarea, content: string, wrap: string, wrapAfter?: string,
      newlines?: string) {
  var startIndex = textarea.selectionStart;
  var endIndex = textarea.selectionEnd;
  var selectedText = textarea.value.substring(startIndex, endIndex);
  var textBefore = textarea.value.substring(0, startIndex);
  var textAfter = textarea.value.substring(endIndex);

  if (_.isUndefined(wrapAfter)) wrapAfter = wrap;
  if (selectedText) content = selectedText;
  if (!newlines) newlines = '';

  return textBefore + newlines + wrap + content + (wrapAfter || '') + newlines + textAfter;
}


var previewHelpMessage = {
  id: 'EdH7MF24',
  version: 1,
  content:
      r.span({}, "Here you can preview how your post will look.",
        r.br(), "You cannot type here.")
};


var newCategoryPlaceholderText =
    "Replace this first paragraph with a short description of this category.\n" +
    "Please keep it short — the text will appear on the category list page.]\n" +
    "\n" +
    "Here, after the first paragraph, you can add a longer description, with\n" +
    "for example category guidelines or rules.\n" +
    "\n" +
    "Below in the comments section, you can discuss this category. For example,\n" +
    "should it be merged with another category? Or should it be split\n" +
    "into many categories?\n";


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
