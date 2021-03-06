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
   module debiki2.util {
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


export interface StupidDialogStuff {
  dialogClassName?: string;
  //header?: any;
  body?: any;
  closeButtonTitle?: any;
}


/**
 * Makes a function that returns a simple dialog that you can use for dialogs
 * like: "Wrong password [Okay]" i.e. only a simple message and a close button.
 */
export function makeStupidDialogGetter() {
  var stupidDialog;

  return function() {
    if (!stupidDialog) {
      stupidDialog = ReactDOM.render(StupidDialog(), utils.makeMountNode());
    }
    return stupidDialog;
  }
}


var getDefaultStupidDialog = makeStupidDialogGetter();

export function openDefaultStupidDialog(stuff: StupidDialogStuff) {
  getDefaultStupidDialog().open(stuff);
}


export var StupidDialog = createComponent({
  getInitialState: function () {
    return { isOpen: false };
  },

  open: function(stuff: StupidDialogStuff) {
    this.setState({ isOpen: true, stuff: stuff });
  },

  close: function() {
    this.setState({ isOpen: false });
  },

  render: function () {
    var stuff: StupidDialogStuff = this.state.stuff || {};
    var body = stuff.body;
    //if (_.isString(body)) {  -- why this if?
      body = ModalBody({},
        r.div({ style: { marginBottom: '2em' }}, body),
        Button({ onClick: this.close }, stuff.closeButtonTitle || "Okay"));
    /*}
    else if (body) {
      die("Non-string content not implemented [EsE7KYKW2]");
    }*/
    // var defaultFooter = () => ModalFooter({}, Button({ onClick: this.close }, 'Okay'));
    return (
      Modal({ show: this.state.isOpen, onHide: this.close, dialogClassName: stuff.dialogClassName },
        //stuff.header,
        body));
        // stuff.footer || defaultFooter()));
  }
});


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
