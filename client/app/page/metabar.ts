/*
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

/// <reference path="../../typedefs/react/react.d.ts" />
/// <reference path="../ReactStore.ts" />
/// <reference path="../react-elements/name-login-btns.ts" />

//------------------------------------------------------------------------------
   module debiki2.page {
//------------------------------------------------------------------------------

var d = { i: debiki.internal, u: debiki.v0.util };
var r = React.DOM;
var reactCreateFactory = React['createFactory'];
var ReactBootstrap: any = window['ReactBootstrap'];
var Button = reactCreateFactory(ReactBootstrap.Button);
var DropdownButton = reactCreateFactory(ReactBootstrap.DropdownButton);
var MenuItem = reactCreateFactory(ReactBootstrap.MenuItem);


/**
 * Shows meta information about the page: created by, when, num replies,
 * message members (if is a private message page), summarize replies button, etc.
 */
export var Metabar = createComponent({
  getInitialState: function() {
    return {
      store: debiki2.ReactStore.allData(),
      ui: { showDetails: false }
    };
  },

  componentDidMount: function() {
    debiki2.ReactStore.addChangeListener(this.onChange);
  },

  componentWillUnmount: function() {
    debiki2.ReactStore.removeChangeListener(this.onChange);
  },

  onChange: function() {
    this.setState({
      store: debiki2.ReactStore.allData(),
      ui: this.state.ui
    });
  },

  onReplyClick: function() {
    d.i.showReplyFormEmbeddedComments();
  },

  onToggleDetailsClick: function() {
    this.state.ui.showDetails = !this.state.ui.showDetails;
    this.setState(this.state);
  },

  render: function() {
    var store: Store = this.state.store;
    var ui = this.state.ui;
    var me: Myself = store.me;

    var notfLevelElem = me.isAuthenticated && !ui.showDetails
      ? r.span({ className: 'dw-page-notf-level', onClick: this.onToggleDetailsClick },
          'Notifications: ' + me.rolePageSettings.notfLevel)
      : null;

    var toggleDetailsBtn =
        r.button({ className: 'dw-cmts-tlbr-open', onClick: this.onToggleDetailsClick },
          r.span({ className: (ui.showDetails ? 'icon-up-open' : 'icon-down-open') }))

    var nameLoginBtns = store.isInEmbeddedCommentsIframe ?
        r.li({}, reactelements.NameLoginBtns({})) : null;

    var summaryElem =
      r.div({ className: 'dw-cmts-tlbr-head' },
          r.ul({ className: 'dw-cmts-tlbr-summary' },
              r.li({ className: 'dw-cmts-count' }, store.numPostsRepliesSection + " replies"),
              nameLoginBtns,
              r.li({}, notfLevelElem)),
          toggleDetailsBtn);

    var detailsElem = ui.showDetails
      ? MetabarDetails(store)
      : null;

    var anyExtraMeta;
    if (store.pageRole === PageRole.Message) {
      var members = store_getPageMembersList(store);
      var memberList = members.map((user) => {
        return (
            r.div({ className: 'esMetabar_msgMmbr', key: user.id },
              avatar.Avatar({ user: user, tiny: true }),
              r.span({ className: 'esMetabar_msgMmbr_username' }, user.username)));
      });
      anyExtraMeta =
          r.div({ className: 'esMetabar_extra' },
            r.div({ className: 'icon-mail' }, "Message"),
            r.div({ className: 'esMetabar_msgMmbrs' },
              memberList));
    }

    var result;
    if (store.isInEmbeddedCommentsIframe) {
      // There's not root post with a reply button, so add a reply button.
      // And an admin button, if is admin.
      var adminLink;
      if (me.isAdmin) {
        adminLink =
          r.a({ className: 'dw-a dw-a-reply', href: d.i.serverOrigin + '/-/admin/#/moderation',
              target: '_blank' }, 'Administrate');
      }
      result =
        r.div({},
          r.div({ className: 'dw-t dw-depth-0 dw-ar-t' },
            r.div({ className: 'dw-p-as dw-as' },
              r.a({ className: 'dw-a dw-a-reply icon-reply', onClick: this.onReplyClick },
                'Reply'),
              adminLink)),
          r.div({ className: 'dw-cmts-tlbr esMetabar' },
            summaryElem,
            detailsElem,
            anyExtraMeta));
    }
    else {
      result =
        r.div({ className: 'dw-cmts-tlbr esMetabar', id: 'dw-cmts-tlbr' },
          summaryElem,
          detailsElem,
          anyExtraMeta);
    }

    return result;
  }
});


var MetabarDetails = createComponent({
  getInitialState: function() {
    return { numRepliesSummarized: null };
  },

  onNewNotfLevel: function(event, newLevel) {
    ReactActions.setPageNoftLevel(newLevel);
  },

  summarizeReplies: function() {
    ReactActions.summarizeReplies();
    setTimeout(() => {
      this.setState({ numRepliesSummarized: $('.dw-p.dw-x').length });
    }, 1);
  },

  render: function() {
    var user = this.props.user;
    var userAuthenticated = user && user.isAuthenticated;

    var notificationsElem = userAuthenticated
        ? DropdownButton({ title: user.rolePageSettings.notfLevel, id: '7bw3gz5',
              className: 'dw-notf-level', onSelect: this.onNewNotfLevel },
            MenuItem({ eventKey: 'Watching' }, 'Watching'),
            MenuItem({ eventKey: 'Tracking' }, 'Tracking'),
            MenuItem({ eventKey: 'Regular' }, 'Regular'),
            MenuItem({ eventKey: 'Muted' }, 'Muted'))
        : null;

    var doneSummarizing = _.isNumber(this.state.numRepliesSummarized)
      ? r.span({ style: { marginLeft: '1em' }}, "Done. Summarized " +
            this.state.numRepliesSummarized + " replies.")
      : null;

    var summarizeButton =
        r.div({ className: 'dw-tlbr-sctn' },
          Button({ onClick: this.summarizeReplies }, "Summarize Replies"), doneSummarizing);

    return (
      r.div({ className: 'dw-cmts-tlbr-details' },
          notificationsElem,
          summarizeButton));
  }
});


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=tcqwn list
