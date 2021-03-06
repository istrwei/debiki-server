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

/// <reference path="../../typedefs/react/react.d.ts" />
/// <reference path="../plain-old-javascript.d.ts" />
/// <reference path="../utils/react-utils.ts" />
/// <reference path="../ReactStore.ts" />
/// <reference path="../Server.ts" />

//------------------------------------------------------------------------------
   module debiki2.pagedialogs {
//------------------------------------------------------------------------------

var d = { i: debiki.internal, u: debiki.v0.util };
var r = React.DOM;
var reactCreateFactory = React['createFactory'];
var ReactBootstrap: any = window['ReactBootstrap'];
var Button = reactCreateFactory(ReactBootstrap.Button);
var Modal = reactCreateFactory(ReactBootstrap.Modal);
var ModalHeader = reactCreateFactory(ReactBootstrap.ModalHeader);
var ModalTitle = reactCreateFactory(ReactBootstrap.ModalTitle);
var ModalBody = reactCreateFactory(ReactBootstrap.ModalBody);
var ModalFooter = reactCreateFactory(ReactBootstrap.ModalFooter);


var seeWrenchDialog;


export function openSeeWrenchDialog() {
  if (!seeWrenchDialog) {
    seeWrenchDialog = ReactDOM.render(SeeWrenchDialog(), utils.makeMountNode());
  }
  seeWrenchDialog.open();
}


var SeeWrenchDialog = createComponent({
  getInitialState: function () {
    return {
      isOpen: false,
    };
  },

  open: function(post: Post) {
    this.setState({ isOpen: true });
  },

  close: function() {
    this.setState({ isOpen: false });
  },

  render: function () {
    var content =
      r.div({},
        r.p({}, "To pin this topic, or to move posts, or do other stuff, click ",
          r.a({ className: 'icon-wrench fake-tools-button' }, "Tools"),
          " to the upper right, instead."));
    return (
      Modal({ show: this.state.isOpen, onHide: this.close, dialogClassName: 'esSeeWrenchDlg' },
        ModalHeader({}, ModalTitle({}, "Look elsewhere")),
        ModalBody({}, content),
        ModalFooter({}, Button({ onClick: this.close }, 'Okay'))));
  }
});


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
