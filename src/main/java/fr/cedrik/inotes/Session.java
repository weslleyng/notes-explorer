/**
 *
 */
package fr.cedrik.inotes;

import java.io.IOException;
import java.net.HttpCookie;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.apache.commons.lang3.CharEncoding;
import org.apache.commons.lang3.text.translate.EntityArrays;
import org.apache.commons.lang3.text.translate.LookupTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;

/**
 * @author C&eacute;drik LIME
 */
public class Session {
	protected final Logger logger = LoggerFactory.getLogger(this.getClass());
	protected final HttpContext context = new HttpContext();
	protected final Set<String> toDelete = new HashSet<String>();
	protected final Set<String> toMarkUnread = new HashSet<String>();
	protected final Set<String> toMarkRead = new HashSet<String>();
	protected MessagesMetaData messages = null;
	protected boolean isLoggedIn = false;

	static {
//		System.setProperty("java.util.logging.config.file", "logging.properties");//XXX DEBUG
	}

	public Session() {
	}

	protected void trace(ClientHttpRequest httpRequest, ClientHttpResponse httpResponse) throws IOException {
		if (logger.isDebugEnabled()) {
			logger.debug(httpRequest.getMethod().toString() + ' ' + httpResponse.getStatusCode() + ' ' + httpResponse.getStatusText() + ' ' + httpRequest.getURI());
		}
	}
	private void traceBody(ClientHttpResponse httpResponse) throws IOException {
		if (logger.isTraceEnabled()) {
			String responseBody = IOUtils.toString(httpResponse.getBody(), context.getCharset(httpResponse));
			logger.trace(responseBody);
		}
	}

	public void setServerAddress(String url) {
		if (isLoggedIn) {
			throw new IllegalStateException();
		}
		context.iNotes.setServerAddress(url);
	}

	public boolean login(String username, String password) throws IOException {
		context.setUserName(username);
		context.setUserPassword(password);
		return login();
	}

	public boolean login() throws IOException {
		Map<String, Object> params = new HashMap<String, Object>();

		// Step 1a: login (auth)
		params.clear();
		params.put("%%ModDate", "0000000100000000");
		params.put("RedirectTo", "/dwaredirect.nsf");
		params.put("password", context.getUserPassword());
		params.put("username", context.getUserName());
		ClientHttpRequest httpRequest = context.createRequest(new URL(context.getServerAddress() + "/names.nsf?Login"), HttpMethod.POST, params);
		ClientHttpResponse httpResponse = httpRequest.execute();
		trace(httpRequest, httpResponse);
//		traceBody(httpResponse);// DEBUG
		try {
			if (httpResponse.getStatusCode().series().equals(HttpStatus.Series.REDIRECTION)) {
				logger.info("Authentication successful for user \"" + context.getUserName() + '"');
			} else if (httpResponse.getStatusCode().series().equals(HttpStatus.Series.SUCCESSFUL)) {
				// body will contain "Invalid username or password was specified."
				logger.warn("ERROR while authenticating user \""+context.getUserName()+"\". Please check your parameters in " + INotesProperties.FILE);
				return false;
			} else {
				logger.error("Unknowng server response while authenticating user \""+context.getUserName()+"\": " + httpResponse.getStatusCode() + ' ' + httpResponse.getStatusText());
				return false;
			}
		} finally {
			httpResponse.close();
		}

		// Step 1b: login (iNotesSRV + base url)
		params.clear();
		httpRequest = context.createRequest(new URL(context.getServerAddress() + "/dwaredirect.nsf"), HttpMethod.GET, params);
		httpResponse = httpRequest.execute();
		trace(httpRequest, httpResponse);
		String responseBody = IOUtils.toString(httpResponse.getBody(), context.getCharset(httpResponse));
		httpResponse.close();
		// search for additional cookie
//		Pattern jsCookie = Pattern.compile("<script language=javascript>document\\.cookie='([^']+)';</script>", Pattern.CASE_INSENSITIVE);
		Pattern jsCookie = Pattern.compile("script language=javascript>document\\.cookie='([^']+)';</script>", Pattern.CASE_INSENSITIVE);
		Matcher jsCookieMatcher = jsCookie.matcher(responseBody);
		assert jsCookieMatcher.groupCount() == 1 ; jsCookieMatcher.groupCount();
		while (jsCookieMatcher.find()) {
			String cookieStr = jsCookieMatcher.group(1);
			logger.trace("Found additional cookie: {}", cookieStr);
			List<HttpCookie> cookies = HttpCookie.parse(cookieStr);
			for (HttpCookie cookie : cookies) {
				logger.trace("Adding cookie: {}", cookie);
				context.getCookieStore().add(httpRequest.getURI(), cookie);
				context.getHttpHeaders().put("Cookie", cookie.toString());//XXX hack, since the previous line does not work correctly
			}
		}
		// search for redirect
		final String redirectURL;
		Pattern htmlRedirect = Pattern.compile("<META HTTP-EQUIV=\"refresh\" content=\"\\d;URL=([^\"]+)\">", Pattern.CASE_INSENSITIVE);
		Matcher htmlRedirectMatcher = htmlRedirect.matcher(responseBody);
		assert htmlRedirectMatcher.groupCount() == 1 ; htmlRedirectMatcher.groupCount();
		if (htmlRedirectMatcher.find()) {
			redirectURL = htmlRedirectMatcher.group(1);
			logger.trace("Found redirect URL: {}", redirectURL);
		} else {
			logger.error("Can not find the redirect URL; aborting. Response body:\n" + responseBody);
			return false;
		}
		if (htmlRedirectMatcher.find()) {
			logger.error("Found more than 1 redirect URL; aborting. Response body:\n" + responseBody);
			return false;
		}

		// Step 1c: base URL
		String baseURL = redirectURL.substring(0, redirectURL.indexOf(".nsf")+".nsf".length()) + '/';
		context.setProxyBaseURL(baseURL + "iNotes/Proxy/?OpenDocument");
		context.setFolderBaseURL(baseURL);
		context.setMailEditBaseURL(baseURL + "iNotes/Mail/?EditDocument");
		logger.trace("Proxy base URL for user \"{}\": {}", context.getUserName(), context.getProxyBaseURL());
		logger.trace("Folder base URL for user \"{}\": {}", context.getUserName(), context.getFolderBaseURL());
		logger.trace("Mail edit base URL for user \"{}\": {}", context.getUserName(), context.getMailEditBaseURL());

		// Step 1d: login (ShimmerS)
		params.clear();
		httpRequest = context.createRequest(new URL(redirectURL), HttpMethod.GET, params);
		httpResponse = httpRequest.execute();
		trace(httpRequest, httpResponse);
//		traceBody(httpResponse);// DEBUG
		// Apparently we don't need to parse the embeded JS to set the "Shimmer" cookie.
		httpResponse.close();

		// Step 1e: login (X-IBM-INOTES-NONCE)
		List<HttpCookie> cookies = context.getCookieStore().getCookies();
		for (HttpCookie cookie : cookies) {
			if ("ShimmerS".equals(cookie.getName())) {
				if (context.getHttpHeaders().containsKey("X-IBM-INOTES-NONCE")) {
					logger.error("Multiple cookies \"ShimmerS\" in store; aborting.");
					return false;
				}
				String xIbmINotesNonce;
				Pattern shimmerS = Pattern.compile("&N:(\\p{XDigit}+)");
				Matcher shimmerSMatcher = shimmerS.matcher(cookie.getValue());
				assert shimmerSMatcher.groupCount() == 1 ; shimmerSMatcher.groupCount();
				if (shimmerSMatcher.find()) {
					xIbmINotesNonce = shimmerSMatcher.group(1);
					logger.trace("Found X-IBM-INOTES-NONCE: {}", xIbmINotesNonce);
				} else {
					logger.error("Can not find X-IBM-INOTES-NONCE; aborting. ShimmerS cookie: " + cookie);
					return false;
				}
				if (shimmerSMatcher.find()) {
					logger.error("Found more than 1 X-IBM-INOTES-NONCE; aborting. ShimmerS cookie: " + cookie);
					return false;
				}
				context.getHttpHeaders().put("X-IBM-INOTES-NONCE", xIbmINotesNonce);
			}
		}

		isLoggedIn = true;
		return true;
	}

	protected void checkLoggedIn() {
		if (! isLoggedIn) {
			throw new IllegalStateException();
		}
	}

	//FIXME iNotes limits the number of results to 1000. Need to paginate.
	public MessagesMetaData getMessagesMetaData() throws IOException {
		checkLoggedIn();
		if (messages != null) {
			return messages;
		}
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("charset", CharEncoding.UTF_8);
		params.put("Form", "s_ReadViewEntries");
//		params.put("PresetFields", "DBQuotaInfo;1,FolderName;"+context.getNotesFolderName()+",UnreadCountInfo;1,s_UsingHttps;1,hc;$98,noPI;1");
		params.put("TZType", "UTC");
		params.put("Start", "1");
		params.put("Count", Short.toString(Short.MAX_VALUE));
		params.put("resortdescending", "5");
		ClientHttpRequest httpRequest = context.createRequest(new URL(context.getProxyBaseURL()+"&PresetFields=DBQuotaInfo;1,FolderName;"+context.getNotesFolderName()+",UnreadCountInfo;1,s_UsingHttps;1,hc;$98,noPI;1"), HttpMethod.GET, params);
		ClientHttpResponse httpResponse = httpRequest.execute();
		trace(httpRequest, httpResponse);
//		traceBody(httpResponse);// DEBUG
		try {
			messages = new XMLConverter().convertXML(httpResponse.getBody());
		} finally {
			httpResponse.close();
		}
		Collections.reverse(messages.entries);
		logger.trace("Loaded {} messages metadata", Integer.valueOf(messages.entries.size()));
		return messages;
	}

	public String getMessageMIMEHeaders(MessageMetaData message) throws IOException {
		checkLoggedIn();
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("charset", CharEncoding.UTF_8);
		params.put("Form", "l_MailMessageHeader");
		ClientHttpRequest httpRequest = context.createRequest(new URL(context.getFolderBaseURL()+message.unid+"/?OpenDocument"), HttpMethod.GET, params);
		ClientHttpResponse httpResponse = httpRequest.execute();
		trace(httpRequest, httpResponse);
		LineIterator responseLines = IOUtils.lineIterator(httpResponse.getBody(), context.getCharset(httpResponse));
		// delete html tags
		String result = cleanHtml(responseLines);
		httpResponse.close();
		if (message.unread) {
			// exporting (read MIME) marks mail as read. Need to get the read/unread information and set it back!
			toMarkUnread.add(message.unid);
		}
		if (logger.isTraceEnabled()) {
			logger.trace("Message MIME headers for " + message.unid + '\n' + result);
		}
		return result;
	}

	public String getMessageMIME(MessageMetaData message) throws IOException {
		checkLoggedIn();
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("charset", CharEncoding.UTF_8);
		params.put("Form", "l_MailMessageHeader");
//		params.put("PresetFields", "FullMessage;1");
		ClientHttpRequest httpRequest = context.createRequest(new URL(context.getFolderBaseURL()+message.unid+"/?OpenDocument&PresetFields=FullMessage;1"), HttpMethod.GET, params);
		ClientHttpResponse httpResponse = httpRequest.execute();
		trace(httpRequest, httpResponse);
		LineIterator responseLines = IOUtils.lineIterator(httpResponse.getBody(), context.getCharset(httpResponse));
		// delete html tags
		String result = cleanHtml(responseLines);
		httpResponse.close();
		if (message.unread) {
			// exporting (read MIME) marks mail as read. Need to get the read/unread information and set it back!
			toMarkUnread.add(message.unid);
		}
		if (logger.isTraceEnabled()) {
			logger.trace("Message MIME " + message + '\n' + result);
		}
		return result;
	}

	private String cleanHtml(LineIterator lines) {
		StringBuilder result = new StringBuilder(16*1024);
		while (lines.hasNext()) {
			String line = lines.nextLine();
			if (line.endsWith("<br>")) {
				line = line.substring(0, line.length()-"<br>".length());
			}
			// convert &quot; -> ", &amp; -> &, &lt; -> <, &gt; -> >
			line = new LookupTranslator(EntityArrays.BASIC_UNESCAPE()).translate(line);
			result.append(line).append("\r\n");
		}
		return result.toString();
	}

	/**
	 * will be done server-side on logout
	 * @param messages
	 * @throws IOException
	 */
	public void deleteMessage(MessageMetaData... messages) throws IOException {
		checkLoggedIn();
		for (MessageMetaData message : messages) {
			toDelete.add(message.unid);
		}
	}

	public void undeleteAllMessages() {
		checkLoggedIn();
		toDelete.clear();
	}

	protected void doDeleteMessages() throws IOException {
		if (toDelete.isEmpty()) {
			return;
		}
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("Form", "l_HaikuErrorStatusJSON");
		params.put("ui", "dwa_form");
		params.put("h_EditAction", "h_Next");
		params.put("h_SetReturnURL", "[[./&Form=s_CallBlankScript]]");
		params.put("h_AllDocs", "");
		params.put("h_FolderStorage", "");
		params.put("s_ViewName", context.getNotesFolderName());
		params.put("h_SetCommand", "h_DeletePages");
		params.put("h_SetEditNextScene", "l_HaikuErrorStatusJSON");
		params.put("h_SetDeleteList", collectionToDelimitedString(toDelete, ';'));
		params.put("h_SetDeleteListCS", "");
		ClientHttpRequest httpRequest = context.createRequest(new URL(context.getMailEditBaseURL()), HttpMethod.POST, params);
		ClientHttpResponse httpResponse = httpRequest.execute();
		trace(httpRequest, httpResponse);
//		traceBody(httpResponse);// DEBUG
		httpResponse.close();
		logger.debug("Deleted (moved to Trash) {} messsage(s)", toDelete.size());
		toDelete.clear();
	}

	/**
	 * will be done server-side on logout
	 * @param messages
	 * @throws IOException
	 */
	public void markMessagesRead(MessageMetaData... messages) throws IOException {
		checkLoggedIn();
		for (MessageMetaData message : messages) {
			toMarkUnread.remove(message.unid);
			toMarkRead.add(message.unid);
		}
	}

	protected void doMarkMessagesRead() throws IOException {
		if (toMarkRead.isEmpty()) {
			return;
		}
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("Form", "l_HaikuErrorStatusJSON");
		params.put("ui", "dwa_form");
		params.put("s_ViewName", context.getNotesFolderName());
		params.put("h_AllDocs", "");
		params.put("h_SetCommand", "h_ShimmerMarkRead");
		params.put("h_SetReturnURL", "[[./&Form=s_CallBlankScript]]");
		params.put("h_EditAction", "h_Next");
		params.put("h_SetEditNextScene", "l_HaikuErrorStatusJSON");
		params.put("h_SetDeleteList", collectionToDelimitedString(toMarkRead, ';'));
		params.put("h_SetDeleteListCS", "");
		ClientHttpRequest httpRequest = context.createRequest(new URL(context.getMailEditBaseURL()), HttpMethod.POST, params);
		ClientHttpResponse httpResponse = httpRequest.execute();
		trace(httpRequest, httpResponse);
//		traceBody(httpResponse);// DEBUG
		httpResponse.close();
		logger.debug("Marked {} messsage(s) as read", toMarkRead.size());
		toMarkRead.clear();
	}

	/**
	 * will be done server-side on logout
	 * @param messages
	 * @throws IOException
	 */
	public void markMessagesUnread(MessageMetaData... messages) throws IOException {
		checkLoggedIn();
		for (MessageMetaData message : messages) {
			toMarkRead.remove(message.unid);
			toMarkUnread.add(message.unid);
		}
	}

	protected void doMarkMessagesUnread() throws IOException {
		if (toMarkUnread.isEmpty()) {
			return;
		}
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("Form", "l_HaikuErrorStatusJSON");
		params.put("ui", "dwa_form");
//		params.put("PresetFields", "s_NoMarkRead;1");
		params.put("s_ViewName", context.getNotesFolderName());
		params.put("h_AllDocs", "");
		params.put("h_SetCommand", "h_ShimmerMarkUnread");
		params.put("h_SetReturnURL", "[[./&Form=s_CallBlankScript]]");
		params.put("h_EditAction", "h_Next");
		params.put("h_SetEditNextScene", "l_HaikuErrorStatusJSON");
		params.put("h_SetDeleteList", collectionToDelimitedString(toMarkUnread, ';'));
		params.put("h_SetDeleteListCS", "");
		ClientHttpRequest httpRequest = context.createRequest(new URL(context.getMailEditBaseURL()+"&PresetFields=s_NoMarkRead;1"), HttpMethod.POST, params);
		ClientHttpResponse httpResponse = httpRequest.execute();
		trace(httpRequest, httpResponse);
//		traceBody(httpResponse);// DEBUG
		httpResponse.close();
		logger.debug("Marked {} messsage(s) as unread", toMarkUnread.size());
		toMarkUnread.clear();
	}

	public boolean logout() throws IOException {
		if (! isLoggedIn) {
			return true;
		}
		// do mark messages unread
		doMarkMessagesUnread();
		// do mark messages read
		doMarkMessagesRead();
		// do delete messages
		doDeleteMessages();
		// and now: logout!
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("Form", "s_Logout");
//		params.put("PresetFields", "s_CacheScrubType;0");
		ClientHttpRequest httpRequest = context.createRequest(new URL(context.getProxyBaseURL()+"&PresetFields=s_CacheScrubType;0"), HttpMethod.GET, params);
		ClientHttpResponse httpResponse = httpRequest.execute();
		trace(httpRequest, httpResponse);
//		traceBody(httpResponse);// DEBUG
		try {
			if (httpResponse.getStatusCode().series().equals(HttpStatus.Series.SUCCESSFUL)) {
				logger.info("Logout successful for user \"" + context.getUserName() + '"');
			} else if (httpResponse.getStatusCode().series().equals(HttpStatus.Series.SUCCESSFUL)) {
				// body will contain "Invalid username or password was specified."
				logger.warn("ERROR while logging out user \""+context.getUserName()+"\".");
				return false;
			}
		} finally {
			httpResponse.close();
		}
		context.getCookieStore().removeAll();
		isLoggedIn = false;
		return true;
	}


	/**
	 * Convenience method to return a Collection as a delimited String.
	 * @param coll the Collection to display
	 * @param delim the delimiter to use (probably a ';')
	 * @return the delimited String
	 */
	private static String collectionToDelimitedString(Collection<?> coll, char delim) {
		StringBuilder sb = new StringBuilder();
		Iterator<?> it = coll.iterator();
		while (it.hasNext()) {
			sb.append(it.next());
			if (it.hasNext()) {
				sb.append(delim);
			}
		}
		return sb.toString();
	}

}