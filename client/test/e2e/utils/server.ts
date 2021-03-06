// This file sends requests to the server we're testing. Doesn't start any server.

/// <reference path="../test-types.ts"/>
/// <reference path="../../../../modules/definitely-typed/lodash/lodash.d.ts"/>
/// <reference path="../../../../modules/definitely-typed/node/node.d.ts"/>

import _ = require('lodash');
import assert = require('assert');
import settings = require('./settings');
import logAndDie = require('./log-and-die');
var logUnusual = logAndDie.logUnusual, die = logAndDie.die, dieIf = logAndDie.dieIf;
var logMessage = logAndDie.logMessage;

// Didn't find any Typescript defs.
declare function require(path: string): any;
var syncRequest = require('sync-request');

var xsrfTokenAndCookies;


function initOrDie() {
  var response = syncRequest('GET', settings.mainSiteOrigin);
  dieIf(response.statusCode !== 200,
      "Error getting xsrf token and cookies from " + settings.mainSiteOrigin + " [EsE2FKE3]",
      showResponse(response));

  var cookieString = '';
  var xsrfToken = '';
  var cookies = response.headers['set-cookie'];
  _.each(cookies, function(cookie) {
    // A Set-Cookie header value looks like so: "name=value; options"
    var nameValueStr = cookie.split(';')[0];
    var nameAndValue = nameValueStr.split('=');
    var name = nameAndValue[0];
    var value = nameAndValue[1];
    cookieString += nameValueStr + '; ';
    if (name == 'XSRF-TOKEN') {
      xsrfToken = value;
    }
  });
  dieIf(!xsrfToken, "Got no xsrf token from " + settings.mainSiteOrigin + " [EsE8GLK2]");
  xsrfTokenAndCookies = [xsrfToken, cookieString];
}


function postOrDie(url, data) {
  dieIf(!settings.e2eTestPassword, "No E2E test password specified [EsE2WKG4]");
  logMessage('POST ' + url + ' ...  [EsM5JMMK2]');

  var passwordParam =
      (url.indexOf('?') === -1 ? '?' : '&') + 'e2eTestPassword=' + settings.e2eTestPassword;

  var response = syncRequest('POST', url + passwordParam, { json: data, headers: {
    'X-XSRF-TOKEN': xsrfTokenAndCookies[0],
    'Cookie': xsrfTokenAndCookies[1]
  }});

  dieIf(response.statusCode !== 200, "POST request failed to " + url + " [EsE5GPK02]",
      showResponse(response));
  return {
    statusCode: response.statusCode,
    headers: response.headers,
    bodyJson: function() {
      return JSON.parse(response.getBody('utf8'));
    }
  };
}


function getOrDie(url) {
  dieIf(!settings.e2eTestPassword, "No E2E test password specified [EsE7YKF2]");
  logMessage('GET ' + url);

  var passwordParam =
      (url.indexOf('?') === -1 ? '?' : '&') + 'e2eTestPassword=' + settings.e2eTestPassword;

  var response = syncRequest('GET', url + passwordParam, { headers: {
    'X-XSRF-TOKEN': xsrfTokenAndCookies[0],
    'Cookie': xsrfTokenAndCookies[1]
  }});

  dieIf(response.statusCode !== 200, "GET request failed to " + url + " [EsE8JYT4]",
      showResponse(response));
  return response;
}


function showResponse(response) {
  var bodyString = response.body;
  if (!_.isString(bodyString)) {
    bodyString = response.getBody('utf8');
  }
  return (
      "Response status code: " + response.statusCode + " (should have been 200)\n" +
      showResponseBodyJson(bodyString));
}


function showResponseBodyJson(body) {
  var text = body;
  if (!_.isString(text)) text = JSON.stringify(text);
  return (
  "Response body: ———————————————————————————————————————————————————————————————————\n" +
  text +
  "\n——————————————————————————————————————————————————————————————————————————————————\n");
}


function importSiteData(siteData: SiteData): IdAddress {
  var url = settings.mainSiteOrigin + '/-/import-site';
  var ids = postOrDie(url, siteData).bodyJson();
  dieIf(!ids.id, "No site id in import-site response [EsE7UGK2]",
      showResponseBodyJson(ids));
  return ids;
}


function getLastEmailSenTo(email: string): EmailSubjectBody {
  var response = getOrDie(settings.mainSiteOrigin + '/-/last-e2e-test-email?sentTo=' + email);
  return JSON.parse(response.body);
}


export = {
  initOrDie: initOrDie,
  importSiteData: importSiteData,
  getLastEmailSenTo: getLastEmailSenTo,
};

