# Copyright (c) 2012 Kaj Magnus Lindberg. All rights reserved

import prelude
d = i: debiki.internal, u: debiki.v0.util
bug = d.u.die2



/**
 * Stringifies various ListItem fields, for display in html.
 */
PrettyListItem =

  clearCache: ->
    @_displayPath = null


  displayPath: ->
    @_displayPath ?= @setDisplayPath ''
    @title || @_displayPath


  setDisplayPath: ({ forParentFolder }) ->
    @_displayPath = @path.replace forParentFolder, ''
    # Also drop any query string, e.g. ?view-new-page=<pageid>
    @_displayPath = @_displayPath.replace /\?.*/, ''


  prettyClarifications: ->
    roleClarified = switch @role
      | 'BlogMainPage' => ['blog']
      | _ => []

    isHomePage = @path == '/'
    isIndexPage = last(@path) == '/' && !@isFolder
    locationClarified =
      if isHomePage => ['homepage']
      else if isIndexPage => ['index page']
      else []

    allClarifs = append roleClarified, locationClarified
    clarifsText = allClarifs.join ', '

    if @displayPath!length and clarifsText.length
      clarifsText = '— ' + clarifsText

    clarifsText


  cssClassFolderOrPage: ->
    if @isPage then 'is-page' else 'is-folder'


  cssClassForMark: ->
    if @marks then ' marked-path' else ''


  stringifyImportantMarks: ->
    text = ''
    for mark in @marks || []
      switch mark
      | 'NewUnsaved' => text += ' (new, unsaved)'
    text


  stringifyOtherMarks: ->
    text = ''
    for mark in @marks || []
      switch mark
      | 'NewSaved' => text += ' (new, saved)'
    text




class ListItem implements PrettyListItem

  ~>
    @included = false
    @open = false

  /**
   * Updates this ListItem with data from another list item,
   * without overwriting certain states.
   */
  update = ({ withDataFrom }) ->
    wasOpen = @open
    wasIncluded = @included
    @ <<< withDataFrom
    @open = wasOpen
    @included = wasIncluded
    @clearCache!



class PageListItem extends ListItem

  (page) ~>
    @ <<< page
    @pageId = @id # rename one of them?
    @isPage = true
    if @parentPageId => @isChildPage = true
    if find (== @role), ['BlogMainPage', 'ForumMainPage', 'WikiMainPage']
      @isMainPage = true
    super!

  slug: -> d.i.findPageSlugIn @path

  folderPath: -> d.i.parentFolderOfPage @path

  changeSlug: (newSlug) ->
    @path = d.i.changePageSlugIn @path, to: newSlug
    @clearCache!



class FolderListItem extends ListItem

  (folder) ~>
    @ <<< folder
    @isFolder = true
    super!



/**
 * Lists and creates pages, blogs, forums and wikis.
 *
 * Concerning page creation:
 * Pages (e.g. a blog main page) are created at default locations, e.g.
 * /blog/. Most people don't understand URLs anyway, and they who do,
 * can move it later, via the Move button.
 */
@PathsCtrl = ['$scope', 'AdminService', ($scope, adminService) ->


  getSelectedFolderOrDie = ->
    bug('DwE031Z3') if selectedFolderListItems.length != 1
    selectedFolderListItems[0]


  getSelectedPageOrDie = ->
    bug('DwE83Iw2') if selectedPageListItems.length != 1
    selectedPageListItems[0]


  # i18n: COULD Use http://xregexp.com/ instead of the build in regex
  # engine (so the title and slug will accept non-Latin chars),
  # and ... I suppose I then cannot use the ng-pattern directive,
  # I'll have to write an x-ng-xregexp directive?
  $scope.patterns =
    folderPath: //^/([^?#:\s]+/)?$//
    dummy: //#// # restores Vim's syntax highlighting
                 # (Vim thinks the first # starts a comment)
    pageTitle: //^.*$//
    # The page slug must not start with '-', because then the
    # rest of the slug could be mistaken for the page id.
    # Don't allow the slug to start with '_' — perhaps I'll decide
    # to give '_' some magic meaning in the future?
    pageSlug: //(^$)|(^[\w\d\.][\w\d\._-]*)$//


  $scope.createBlog = (location) ->
    createPage {
        folder: '/blog/'
        pageSlug: ''
        showId: false
        pageRole: 'BlogMainPage' }


  $scope.createDraftPage = ->
    createPageInFolder '/.drafts/'


  $scope.createPageInFolder = ->
    createPageInFolder getSelectedFolderOrDie!path


  createPageInFolder = (parentFolder) ->
    createPage {
        folder: parentFolder
        pageSlug: 'new-page'
        showId: true }


  /**
   * Creates a new page list item, which, from the user's point of view,
   * is similar to creating a new unsaved page.
   *
   * `pageData` should be a:
   *    { folder, pageSlug, showId, (pageRole, parentPageId) }
   * where (...) is optional.
   */
  createPage = (pageData) ->
    adminService.getViewNewPageUrl pageData, (viewNewPageUrl) ->
      page =
          path: viewNewPageUrl
          id: d.i.findPageIdForNewPage viewNewPageUrl
          #title: undefined
          #authors: undefined # should be current user
          role: pageData.pageRole
          parentPageId: pageData.parentPageId

      pageListItem = PageListItem page
      pageListItem.marks = ['NewUnsaved']

      # Select the new page, and nothing else, so the 'View/edit' button
      # appears and the user hopefully understands what to do next.
      pageListItem.included = true
      for item in $scope.listItems
        item.included = false

      listMorePagesDeriveFolders [pageListItem]


  $scope.selectedPage =
    getSelectedPageOrDie


  /**
   * Opens a page in a new browser tab.
   *
   * (If the page was just created, but has not been saved server side,
   * the server will create it lazily if you edit and save it.)
   */
  $scope.viewSelectedPage = ->
    pageItem = getSelectedPageOrDie!
    window.open pageItem.path, '_blank'
    # COULD add callback that if page saved: (see Git stash 196d8accb80b81)
    # pageItem.marks = reject (== 'NewUnsaved'), pageItem.marks
    # pageItem.marks.push 'NewSaved'
    # pageItem.update withDataFrom: ...
    # and then: redrawPageItems [pageItem]


  $scope.moveSelectedPageTo = (newFolder) ->
    pageListItem = getSelectedPageOrDie!
    curFolder = pageListItem.folderPath!
    moveSelectedPages fromFolder: curFolder, toFolder: newFolder


  moveSelectedPages = ({ fromFolder, toFolder }) ->
    refreshPageList = ->
      for pageListItem in selectedPageListItems
        pageListItem.path .= replace fromFolder, toFolder
      redrawPageItems selectedPageListItems

    for pageListItem in selectedPageListItems
      adminService.movePages [pageListItem.pageId],
          { fromFolder, toFolder, callback: refreshPageList }


  $scope.renameSelectedPageTo = ({ newSlug, newTitle }) ->
    refreshPageList = ->
      # pageListItem.title = newTitle
      pageListItem.changeSlug newSlug
      redrawPageItems [pageListItem]

    pageListItem = getSelectedPageOrDie!
    if find (== 'NewUnsaved'), pageListItem.marks || []
      # Page not yet saved, don't call server.
      refreshPageList!
    else
      adminService.renamePage pageListItem.pageId,
          { newSlug, newTitle, callback: refreshPageList }


  $scope.listItems = []


  loadAndListPages = ->
    adminService.listAllPages (data) ->
      listMorePagesDeriveFolders <|
          [PageListItem(page) for page in data.pages]


  redrawPageItems = (pageItems) ->
    # Remove pageItem and add it again, so any missing parent folder
    # is created, in case pageItem has been moved.
    # For now, call listMorePagesDeriveFolders once per pageItem
    # (a tiny bit inefficient).
    for pageItem in pageItems
      $scope.listItems = reject (== pageItem), $scope.listItems
      listMorePagesDeriveFolders [pageItem]


  listMorePagesDeriveFolders = (morePageItems) ->
    newFolderPaths = []

    for item in morePageItems
      # Blog articles should be listed below the relevant blog's main page,
      # but I've not implemented this, so hide them for now, and
      # forum threads and wiki pages too.
      # (They're reachable via the blog/forum/wiki main page anyway.)
      # (Show subforum main pages — for them, both .isChildPage and
      # .isMainPage are true.)
      hide = item.isChildPage && !item.isMainPage
      unless hide
        $scope.listItems.push item
        newFolderPaths.push item.folderPath!

    newFolderPaths = unique newFolderPaths
    newFolderPaths = reject (== '/'), newFolderPaths

    oldFolderPaths =
        [item.path for item in $scope.listItems when item.isFolder]

    for newPath in newFolderPaths when not find (== newPath), oldFolderPaths
      $scope.listItems.push FolderListItem({ path: newPath })

    redrawPageList!


  redrawPageList = ->
    sortItemsInPlace $scope.listItems
    updateListItemFields $scope.listItems
    $scope.updateSelections!


  # Places deep paths at the end. Sorts alphabetically, at each depth.
  sortItemsInPlace = (items) ->
    items.sort (a, b) ->
      partsA = a.path.split '/'
      partsB = b.path.split '/'
      lenA = partsA.length
      lenB = partsB.length
      minLen = Math.min(lenA, lenB)
      for ix from 1 to minLen
        partA = partsA[ix]
        partB = partsB[ix]
        # Sort a folder before the pages in it.
        # (A folder and its index page has identical `path`s, e.g. `/folder/`.)
        if ix + 1 == lenA and lenA == lenB
          return -1 if a.isFolder and b.isPage
          return 1 if b.isFolder and a.isPage
        # Sort pages before folders
        return -1 if ix + 1 == lenA and lenA < lenB
        return 1 if ix + 1 == lenB and lenB < lenA
        # Sort alphabetically
        return -1 if partA < partB
        return 1 if partB < partA
      return 0

  $scope.nothingSelected = true

  selectedPageListItems = []
  selectedFolderListItems = []

  /**
   * Scans $scope.listItems and updates page and folder selection count
   * variables.
   */
  $scope.updateSelections = ->
    selectedPageListItems := []
    selectedFolderListItems := []
    numDrafts = 0
    numNonDrafts = 0
    for item in $scope.listItems when item.included
      if item.pageId
        selectedPageListItems.push item
        #if item.isDraft => numDrafts += 1
        #else numNonDrafts += 1
      else
        selectedFolderListItems.push item

    numPages = selectedPageListItems.length
    numFolders = selectedFolderListItems.length

    $scope.nothingSelected = numPages == 0 && numFolders == 0
    $scope.onePageSelected = numPages == 1 && numFolders == 0
    $scope.oneFolderSelected = numFolders == 1 && numPages == 0

    $scope.onlyDraftsSelected =
        numDrafts > 0 && numNonDrafts == 0 && numFolders == 0
    $scope.onlyPublishedSelected =
        numDrafts == 0 && numNonDrafts > 0 && numFolders == 0

    # In the future, show stats on the selected pages, in a <div> to the
    # right. Or show a preview, if only one single page selected.
    return

  /**
   * Traverses the $scope.listItems list once, checks each item.closed,
   * and updates all hide counts accordingly.
   *
   * COULD remove folders without children.
   * COULD set `open = true` if all children of a folder is shown,
   * even if folder was actually closed. (If a page is marked,
   * e.g. with 'NewUnsaved', it's shown even if parent folder closed.)
   */
  updateListItemFields = (items) ->
    curHideCount = 0
    curParentFolderPath = '/'
    folderStack = []
    # Could, instead?: folderStack = [{ hideCount: 0, path: '/' }]
    for item in items

      # Leave any folder and continue from previously stacked parent.
      if item.isFolder
        curHideCount = 0
        curParentFolderPath = '/'
        while curParentFolderPath == '/' && folderStack.length > 0
          lastFolder = last folderStack
          if item.path.search(lastFolder.path) == 0
            curHideCount = lastFolder.hideCount
            curHideCount += 1 unless lastFolder.open
            curParentFolderPath = lastFolder.path
          else
            folderStack.pop()

      item.depth = folderStack.length
      item.setDisplayPath forParentFolder: curParentFolderPath
      item.hideCount = curHideCount

      # Always show stuff with any marks (could be a page the user
      # just created), and show its ancestor folders too.
      if item.marks
        item.hideCount = 0
        for ancestorFolder in folderStack
          # BUG SHOULD recursively update hide counts of any folder children.
          # (So they're 1 if folder closed, or 0 if open).
          ancestorFolder.hideCount = 0

      # Enter folder?
      if item.isFolder
        curHideCount += 1 unless item.open
        curParentFolderPath = item.path
        folderStack.push item
    return


  $scope.openClose = (item) ->
    item.open = !item.open
    updateListItemFields $scope.listItems


  $scope.test =
    sortItemsInPlace: sortItemsInPlace
    updateListItemFields: updateListItemFields
    PageListItem: PageListItem
    FolderListItem: FolderListItem

  loadAndListPages!

  ]


# vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list