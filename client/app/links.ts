/*
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

// In this file: Constructs links, e.g. to a user's profile page.
// Usage: MenuItemLink({ href: linkToCurrentUserProfilePage(store) }, "View your profile")


//------------------------------------------------------------------------------
   module debiki2 {
//------------------------------------------------------------------------------


export function linkToPageId(pageId: PageId): string {
  return '/-' + pageId;
}


export function linkToAdminPage(): string {
  return '/-/admin/';
}

export function linkToUserInAdminArea(userId: UserId): string {
  return '/-/admin/users/id/' + userId;
}

export function linkToReviewPage(): string {
  return '/-/admin/review/all';
}


export function linkToUserProfilePage(userId: UserId): string {
  return '/-/users/id/' + userId;
}

export function linkToInvitesFromUser(userId: UserId): string {
  return linkToUserProfilePage(userId) + '/invites';
}

export function linkToMyProfilePage(store: Store): string {
  return '/-/users/id/' + store.me.id;
}


export function linkToNotificationSource(notf: Notification): string {
  if (notf.pageId && notf.postNr) {
    return '/-' + notf.pageId + '#post-' + notf.postNr;
  }
  else {
    die("Unknown notification type [EsE5GUKW2]")
  }
}


export function linkToTermsOfUse(): string {
  return '/-/terms-of-use';
}

export function linkToAboutPage(): string {
  return '/about';
}


/**
 * Navigates back to the page that the user viewed before s/he entered the admin or
 * about-user area, or to the homepage ('/') if there's no previous page.
 */
export function goBackToSite() {
  // Hmm, could inline this instead. Was more complicated in the past, when using
  // an URL param instead of sessionStorage.
  var previousUrl = sessionStorage.getItem('returnToSiteUrl') || '/';
  window.location.replace(previousUrl);
}

//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
