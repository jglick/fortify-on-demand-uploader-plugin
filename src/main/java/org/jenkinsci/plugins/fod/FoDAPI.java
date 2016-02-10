/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.jenkinsci.plugins.fod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.TreeMap;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.jenkinsci.plugins.fod.schema.Release;
import org.jenkinsci.plugins.fod.schema.Scan;
import org.jenkinsci.plugins.fod.schema.ScanSnapshot;

/**
 * 
 * @author Kevin.Williams
 * @author Michael.A.Marshall
 */
public class FoDAPI {
	
	private static Logger log = LogManager.getLogManager().getLogger(FoDAPI.class.getName());
	
	private static final String FOD_SCOPE_TENANT = "https://hpfod.com/tenant";
	private static final String UTF_8 = "UTF-8";
	private String sessionToken;
	private long tokenExpiry;
	private String baseUrl;
	private String proxyHostname = null;
	private int proxyPort;
	private Proxy proxy;
	private Gson gson;

	final int seglen = 1024 * 1024; // chunk size
	final long maxFileSize = 5000 * 1024 * 1024L;
	boolean lastFragment = false;
	long bytesSent = 0;
	HttpClient httpClient = null;

	private static final String CLASS_NAME = FoDAPI.class.getName();

	/** Timeout in milliseconds */
	private static final int CONNECTION_TIMEOUT = 30*1000;

	public FoDAPI(String baseUrl) {
		this.baseUrl = baseUrl;
	}
	
	public boolean authorize(String clientId, String clientSecret)
			throws IOException {
		final String METHOD_NAME = CLASS_NAME+".authorize(String,String)";
		PrintStream logger = FodBuilder.getLogger();
		
		logger.println(METHOD_NAME+": clientId = "+clientId);
		logger.println(METHOD_NAME+": clientSecret = "+clientSecret);
		
		if( null == this.httpClient )
		{
			HttpClientBuilder builder = HttpClientBuilder.create();
			if( null != proxyHostname && !proxyHostname.isEmpty() )
			{
				logger.println(METHOD_NAME+": proxyHostname = "+this.proxyHostname);
				logger.println(METHOD_NAME+": proxyPort = "+this.proxyPort);
				HttpHost proxyHttpHost = new HttpHost(this.proxyHostname,this.proxyPort);
				builder.setProxy(proxyHttpHost);
			}
			this.httpClient = builder.build();
		}
		
		logger.println(METHOD_NAME+": url = "+this.baseUrl);
		AuthTokenRequest authRequest = new AuthTokenRequest();
		authRequest.setGrantType(AuthCredentialType.CLIENT_CREDENTIALS.getName());
		authRequest.setScope(FOD_SCOPE_TENANT);
		AuthApiKey principal = new AuthApiKey();
		principal.setClientId(clientId);
		principal.setClientSecret(clientSecret);
		authRequest.setPrincipal(principal );
		long tokenReqSubmitTs = System.currentTimeMillis();
		AuthTokenResponse authResponse = this.authorize(this.baseUrl,authRequest,this.httpClient);
		String token = authResponse.getAccessToken();
		logger.println(METHOD_NAME+": token = "+token);
		if (token != null && 0 < token.length() )
		{
			this.sessionToken = token;
			Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			Integer tokenLifetime = authResponse.getExpiresIn();
			cal.setTimeInMillis(tokenReqSubmitTs);
			cal.add(Calendar.SECOND, tokenLifetime);
			this.tokenExpiry = cal.getTimeInMillis();
			return true;
		}
		return false;
	}

	private AuthTokenResponse authorize(String baseUrl, AuthTokenRequest request, HttpClient client)
	{
		final String METHOD_NAME = CLASS_NAME+".authorize";
		PrintStream out = FodBuilder.getLogger();
		
		//String accessToken = null;
		AuthTokenResponse response = new AuthTokenResponse();
		try
		{
			String endpoint = baseUrl + "/oauth/token";
			HttpPost httppost = new HttpPost(endpoint);
			
			RequestConfig requestConfig = RequestConfig.custom()
				    .setConnectionRequestTimeout(CONNECTION_TIMEOUT)
				    .setConnectTimeout(CONNECTION_TIMEOUT)
				    .setSocketTimeout(CONNECTION_TIMEOUT)
				    .build();
			
			httppost.setConfig(requestConfig);
			
			List<NameValuePair> formparams = new ArrayList<NameValuePair>();
			
			out.println(METHOD_NAME+": request.grantType = "+request.getGrantType());
			
			if( AuthCredentialType.CLIENT_CREDENTIALS.getName().equals(request.getGrantType()) )
			{
				AuthApiKey cred = (AuthApiKey)request.getPrincipal();
				formparams.add(new BasicNameValuePair("scope",FOD_SCOPE_TENANT));
				formparams.add(new BasicNameValuePair("grant_type",request.getGrantType()));
				formparams.add(new BasicNameValuePair("client_id", cred.getClientId()));
				formparams.add(new BasicNameValuePair("client_secret", cred.getClientSecret()));
				
				out.println(METHOD_NAME+": request.scope = "+FOD_SCOPE_TENANT);
				out.println(METHOD_NAME+": request.grantType = "+request.getGrantType());
				out.println(METHOD_NAME+": request.clientId = "+cred.getClientId());
				out.println(METHOD_NAME+": request.clientSecret = "+cred.getClientSecret());
			}
			else if( AuthCredentialType.PASSWORD.getName().equals(request.getGrantType()) )
			{
				AuthUsernamePassword cred = (AuthUsernamePassword)request.getPrincipal();
				formparams.add(new BasicNameValuePair("scope",FOD_SCOPE_TENANT));
				formparams.add(new BasicNameValuePair("grant_type", request.getGrantType()));
				formparams.add(new BasicNameValuePair("username", cred.getTenantCode()+ "\\" + cred.getUsername()));
				formparams.add(new BasicNameValuePair("password", cred.getPassword()));
				
				out.println(METHOD_NAME+": request.scope = "+FOD_SCOPE_TENANT);
				out.println(METHOD_NAME+": request.grantType = "+request.getGrantType());
				out.println(METHOD_NAME+": request.username = "+cred.getUsername());
				out.println(METHOD_NAME+": request.password = "+cred.getPassword());
			}
			else
			{
				out.println(METHOD_NAME+": unrecognized grant type");
			}

			UrlEncodedFormEntity entity = new UrlEncodedFormEntity(formparams,UTF_8);
			httppost.setEntity(entity);
			HttpResponse postResponse = client.execute(httppost);
			StatusLine sl = postResponse.getStatusLine();
			Integer statusCode = Integer.valueOf(sl.getStatusCode());
			out.println(METHOD_NAME+": statusCode = "+statusCode);
			
			if (statusCode.toString().startsWith("2")) {
				HttpEntity respopnseEntity = postResponse.getEntity();
				InputStream is = respopnseEntity.getContent();
				StringBuffer content = collectInputStream(is);
				String x = content.toString();
				JsonParser parser = new JsonParser();
				JsonElement jsonElement = parser.parse(x);
				JsonObject jsonObject = jsonElement.getAsJsonObject();
				JsonElement tokenElement = jsonObject.getAsJsonPrimitive("access_token");
				
				if (null != tokenElement && !tokenElement.isJsonNull() && tokenElement.isJsonPrimitive()) {
					//accessToken = tokenElement.getAsString();
					response.setAccessToken(tokenElement.getAsString());
					out.println(METHOD_NAME+": access_token = "+tokenElement.getAsString());
				}
				JsonElement expiresIn = jsonObject.getAsJsonPrimitive("expires_in");
				Integer expiresInInt = expiresIn.getAsInt();
				response.setExpiresIn(expiresInInt);
				out.println(METHOD_NAME+": expires_in = "+expiresInInt);
				//TODO handle remaining two fields in response
			}

		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (ClientProtocolException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return response;
	}

	public boolean isLoggedIn()
	{
		final String METHOD_NAME = CLASS_NAME + ".isLoggedIn";
		PrintStream out = FodBuilder.getLogger();
		out.println(METHOD_NAME+": sessionToken = "+sessionToken);
		out.println(METHOD_NAME+": tokenExpiry = "+tokenExpiry+" ("+(new Date(tokenExpiry))+")");
		
		boolean tokenValid = 
				null != sessionToken
				&& 0 < sessionToken.length()
				&& System.currentTimeMillis() < tokenExpiry
				;
		
		out.println(METHOD_NAME+": tokenValid = "+tokenValid);
		return tokenValid;
	}

	public Map<String, String> getAssessmentTypeList() throws IOException {
		String endpoint = baseUrl + "/api/v1/assessmenttype";
		URL url = new URL(endpoint);
		HttpURLConnection connection = getHttpUrlConnection("GET",url);

		// Get Response
		InputStream is = connection.getInputStream();
		StringBuffer response = collectInputStream(is);
		JsonArray arr = getDataJsonArray(response);
		Map<String, String> map = new TreeMap<String, String>();
		
		for (int ix = 0; ix < arr.size(); ix++) {
			JsonElement entity = arr.get(ix);
			JsonObject obj = entity.getAsJsonObject();
			JsonPrimitive name = obj.getAsJsonPrimitive("Name");
			JsonPrimitive id = obj.getAsJsonPrimitive("AssessmentTypeId");
			if (map.containsKey(name.getAsString()))
				continue;
			map.put(name.getAsString(), id.getAsString());
		}

		return map;
	}

	public Map<String, String> getApplicationList() throws IOException {
		final String METHOD_NAME = CLASS_NAME+".getApplicationList";
		
		//String endpoint = baseUrl + "/api/v1/application";
		String endpoint = baseUrl + "/api/v1/Application/?fields=applicationId,applicationName,isMobile";
		URL url = new URL(endpoint);
		HttpURLConnection connection = getHttpUrlConnection("GET",url);

		// Get Response
		InputStream is = connection.getInputStream();
		StringBuffer response = collectInputStream(is);
		JsonArray arr = getDataJsonArray(response);
		Map<String, String> map = new TreeMap<String, String>();
		
		for (int ix = 0; ix < arr.size(); ix++)
		{
			JsonElement entity = arr.get(ix);
			JsonObject obj = entity.getAsJsonObject();
			JsonPrimitive name = obj.getAsJsonPrimitive("applicationName");
			JsonPrimitive id = obj.getAsJsonPrimitive("applicationID");
			JsonPrimitive isMobile = obj.getAsJsonPrimitive("isMobile");
			
			if (map.containsKey(name.getAsString()))
			{
				continue;
			}
			if ( !isMobile.getAsBoolean() )
			{
				map.put(name.getAsString(), id.getAsString());
			}
		}

		return map;
	}
	
	public Long getReleaseId(String applicationName, String releaseName) throws IOException
	{
		//TODO no exact way to look up single release found yet -- ask FoD ppl if easier method exists
		Long releaseId = null;
		List<Release> releaseList = getReleaseList(applicationName);
		for(Release release : releaseList)
		{
			if( null != release 
					&& null != release.getReleaseId() 
					&& releaseName.equalsIgnoreCase(release.getReleaseName()) )
			{
				releaseId = release.getReleaseId();
			}
		}
		
		return releaseId;
	}

	public Release getRelease(Long releaseId) throws IOException
	{
		// https://www.hpfod.com/api/v1/Release/93328
		// https://www.hpfod.com/api/v2/Releases/93328
		
		String endpoint = baseUrl+"/api/v2/Releases/"+releaseId;
		URL url = new URL(endpoint);
		HttpURLConnection connection = getHttpUrlConnection("GET",url);

		// Get Response
		InputStream is = connection.getInputStream();
		StringBuffer response = collectInputStream(is);
		String responseStr = response.toString();
		JsonElement dataElement = getDataJsonElement(responseStr);
		
		Gson gson = getGson();
		Release release = gson.fromJson(dataElement, Release.class);
		
		return release;
	}

	/**
	 * Retrieves release info base on application name and release name. 
	 * Please be aware this makes two calls on the back end.
	 * 
	 * @param applicationName
	 * @param releaseName
	 * @return
	 * @throws IOException
	 */
	public Release getRelease(String applicationName, String releaseName) throws IOException
	{
		Long releaseId = getReleaseId(applicationName, releaseName);
		Release release = getRelease(releaseId);
		
		return release;
	}
	
	/**
	 * Retrieves all releases for the tenant, with a limited number of fields populated.
	 * Use the call to retrieve a single release by releaseId if you want all fields populated. 
	 * 
	 * @return
	 * @throws IOException
	 */
	public List<Release> getReleaseList() throws IOException
	{
		final String METHOD_NAME = CLASS_NAME+".getReleaseList";
		
		List<Release> releaseList = new LinkedList<Release>();
		
		String endpoint = baseUrl + "/api/v2/Releases?fields=applicationId,applicationName,releaseId,releaseName";
		URL url = new URL(endpoint);
		HttpURLConnection connection = getHttpUrlConnection("GET",url);

		// Get Response
		InputStream is = connection.getInputStream();
		StringBuffer response = collectInputStream(is);
		JsonArray arr = getDataJsonArray(response);
		
		Gson gson = getGson();
		
		for (int ix = 0; ix < arr.size(); ix++) {
			Release release = new Release();
			
			JsonElement entity = arr.get(ix);
			//JsonObject obj = entity.getAsJsonObject();
			
			release = gson.fromJson(entity, Release.class);

			releaseList.add(release);
		}

		return releaseList;
	}

	/**
	 * Retrieves all releases for a given application name, with a limited number of fields populated.
	 * Use the call to retrieve a single release by releaseId if you want all fields populated. 
	 * 
	 * @param applicationName
	 * @return
	 * @throws IOException
	 */
	public List<Release> getReleaseList(String applicationName)
			throws IOException
	{
		//FIXME URLEncoder deprecated?
		applicationName = URLEncoder.encode(applicationName);
		String endpoint = baseUrl+"/api/v2/Releases/?q=applicationName:"+applicationName+"&fields=applicationId,applicationName,releaseId,releaseName";
		URL url = new URL(endpoint);
		HttpURLConnection connection = getHttpUrlConnection("GET",url);

		// Get Response
		InputStream is = connection.getInputStream();
		StringBuffer response = collectInputStream(is);
		JsonArray arr = getDataJsonArray(response);
		List<Release> list = new LinkedList<Release>();
		
		Gson gson = getGson();
		for (int ix = 0; ix < arr.size(); ix++) {
			JsonElement entity = arr.get(ix);
			Release release = gson.fromJson(entity, Release.class);
			list.add(release);
		}

		return list;
	}

	/**
	 * Retrieves all releases for a given application name, with a limited number of fields populated.
	 * Use the call to retrieve a single release by releaseId if you want all fields populated. 
	 * 
	 * @param applicationId
	 * @return
	 * @throws IOException
	 */
	public List<Release> getReleaseList(Long applicationId)
			throws IOException
	{
		String endpoint = baseUrl+"/api/v2/Releases/?q=applicationId:"+applicationId+"&fields=applicationId,applicationName,releaseId,releaseName";
		URL url = new URL(endpoint);
		HttpURLConnection connection = getHttpUrlConnection("GET",url);

		// Get Response
		InputStream is = connection.getInputStream();
		StringBuffer response = collectInputStream(is);
		JsonArray arr = getDataJsonArray(response);
		List<Release> list = new LinkedList<Release>();
		
		Gson gson = getGson();
		for (int ix = 0; ix < arr.size(); ix++) {
			JsonElement entity = arr.get(ix);
			Release release = gson.fromJson(entity, Release.class);
			list.add(release);
		}

		return list;
	}
	
	/**
	 * Scan snapshot refers to a particular point in history when statistics are 
	 * reevaluated, such as when a scan is completed. This call returns different 
	 * data structures from the call to retrieve an individual scan by ID from
	 * under the Release context path.
	 * 
	 * @throws IOException
	 */
	public List<ScanSnapshot> getScanSnapshotList() throws IOException
	{
		//String endpoint = baseUrl + "/api/v1/Scan/?q=ProjectVersionId:10398"; // this call's parameters are ignored by FoD!!
		String endpoint = baseUrl + "/api/v1/Scan";
		URL url = new URL(endpoint);
		HttpURLConnection connection = getHttpUrlConnection("GET",url);

		// Get Response
		InputStream is = connection.getInputStream();
		StringBuffer response = collectInputStream(is);
		JsonArray arr = getDataJsonArray(response);
		List<ScanSnapshot> snapshots = new LinkedList<ScanSnapshot>();
		
		for (int ix = 0; ix < arr.size(); ix++) {
			JsonElement entity = arr.get(ix);
			JsonObject obj = entity.getAsJsonObject();
			
			//FIXME GSON not setting fields on Scan obj, need to troubleshoot 
			//Scan scan = getGson().fromJson(obj, Scan.class);
			//Scan scan = getGson().fromJson(entity, Scan.class);
			
			ScanSnapshot scan = new ScanSnapshot();
			if( !obj.get("ProjectVersionId").isJsonNull() )
			{
				JsonPrimitive releaseIdObj = obj.getAsJsonPrimitive("ProjectVersionId");
				scan.setProjectVersionId(releaseIdObj.getAsLong());
			}
			if( !obj.get("StaticScanId").isJsonNull() )
			{
				JsonPrimitive staticScanIdObj = obj.getAsJsonPrimitive("StaticScanId");
				scan.setStaticScanId(staticScanIdObj.getAsLong());
			}
			if( !obj.get("DynamicScanId").isJsonNull() )
			{
				JsonPrimitive dynamicScanIdObj = obj.getAsJsonPrimitive("DynamicScanId");
				scan.setDynamicScanId(dynamicScanIdObj.getAsLong());
			}
			if( !obj.get("MobileScanId").isJsonNull() )
			{
				JsonPrimitive mobileScanIdObj = obj.getAsJsonPrimitive("MobileScanId");
				scan.setMobileScanId(mobileScanIdObj.getAsLong());
			}
			//FIXME translate CategoryRollups
			if( !obj.get("RollupHistoryId").isJsonNull() )
			{
				JsonPrimitive rollupHistoryIdObj = obj.getAsJsonPrimitive("RollupHistoryId");
				scan.setHistoryRollupId(rollupHistoryIdObj.getAsLong());
			}
			
			snapshots.add(scan);
		}

		return snapshots;
	}
	
	/**
	 * Scan snapshot refers to a particular point in history when statistics are 
	 * reevaluated, such as when a scan is completed. This call returns different 
	 * data structures from the call to retrieve an individual scan by ID from
	 * under the Release context path.
	 * 
	 * @throws IOException
	 */
	public List<ScanSnapshot> getScanSnapshotList(Long releaseId) throws IOException
	{
		String endpoint = baseUrl + "/api/v2/releases/"+releaseId+"/scan-results";
		
		URL url = new URL(endpoint);
		HttpURLConnection connection = getHttpUrlConnection("GET",url);

		// Get Response
		InputStream is = connection.getInputStream();
		StringBuffer response = collectInputStream(is);
		
		List<ScanSnapshot> scanList = new LinkedList<ScanSnapshot>();
		JsonArray arr = getDataJsonArray(response);
		
		Gson gson = getGson();
		
		for (int ix = 0; ix < arr.size(); ix++) {
			
			JsonElement entity = arr.get(ix);
			
			ScanSnapshot scan = gson.fromJson(entity, ScanSnapshot.class);

			scanList.add(scan);
		}
		
		return scanList;
	}
	
	/**
	 * Retrieves information on a single scan, given a release and scan ID.
	 * The scan ID can be retrieved from one of the getSnapshotList methods, 
	 * or from the fully populated Release object from the getRelease methods.
	 * 
	 * @param releaseId
	 * @param scanId
	 * @return
	 * @throws IOException
	 */
	public Scan getScan(Long releaseId, Long scanId) throws IOException
	{
		// https://www.hpfod.com/api/v2/releases/30008/scan-results
		String endpoint = baseUrl + "/api/v2/Releases/"+releaseId+"/Scans/"+scanId;
		
		URL url = new URL(endpoint);
		HttpURLConnection connection = getHttpUrlConnection("GET",url);

		// Get Response
		InputStream is = connection.getInputStream();
		StringBuffer response = collectInputStream(is);
		
		String responseString = response.toString();
		JsonElement dataObject = getDataJsonElement(responseString);
		
		Gson gson = getGson();
		
		Scan scan = gson.fromJson(dataObject, Scan.class);
		return scan;
	}
	
	/**
	 * If relaseId not on request object, looks up release ID based on application and 
	 * release names, then tries to upload. Requires a zip file containing all relevant
	 * files already be created and referenced from the request object's uploadZip field.
	 * 
	 * @param req
	 * @return
	 * @throws IOException
	 */
	public UploadStatus uploadFile(UploadRequest req) throws IOException
	{
		final String METHOD_NAME = CLASS_NAME +".uploadFile";
		
		UploadStatus status = new UploadStatus();
		PrintStream out = FodBuilder.getLogger();
		
		Long releaseId = req.getReleaseId();
		
		if( null == releaseId || 0 <= releaseId)
		{
			String applicationName = req.getApplicationName();
			String releaseName = req.getReleaseName();
			out.println(METHOD_NAME+": applicationName="+applicationName);
			out.println(METHOD_NAME+": releaseName="+releaseName);
			releaseId = getReleaseId(applicationName, releaseName);
		}
		
		out.println(METHOD_NAME+": releaseId = "+releaseId);
		
		if( null != releaseId && 0 < releaseId )
		{
			if(sessionToken != null && !sessionToken.isEmpty())	
			{
				FileInputStream fs = new FileInputStream(req.getUploadZip());
				//FileInputStream fs = new FileInputStream(zipLocation);
				out.println(METHOD_NAME+": FileInputStream created. Creating buffer of size "+seglen);
				byte[] readByteArray = new byte[seglen];
				byte[] sendByteArray = null;
				int fragmentNumber = 0;
				int byteCount = 0;
				long offset = 0;
				out.println(METHOD_NAME+": reading in file contents ...");
				while((byteCount = fs.read(readByteArray)) != -1)
				{
					out.println(METHOD_NAME+": read in "+byteCount+" bytes of zip file");
					if(byteCount < seglen)
					{
						out.println(METHOD_NAME+": resizing buffer to fit end of file contents");
						fragmentNumber = -1;
						lastFragment = true;
						sendByteArray = Arrays.copyOf(readByteArray, byteCount);
					}
					else
					{
						sendByteArray = readByteArray;
					}
					String fragUrl = "";
					if(req.getLanguageLevel() != null)
					{
						fragUrl = baseUrl + "/api/v1/release/" + releaseId + "/scan/?assessmentTypeId=" + req.getAssessmentTypeId() + "&technologyStack=" + req.getTechnologyStack() + "&languageLevel=" + req.getLanguageLevel() +  "&fragNo=" + fragmentNumber++ + "&len=" + byteCount + "&offset=" + offset;
						out.println(METHOD_NAME+": fragUrl = "+fragUrl);
					}
					else
					{
						fragUrl = baseUrl + "/api/v1/release/" + releaseId + "/scan/?assessmentTypeId=" + req.getAssessmentTypeId() + "&technologyStack=" + req.getTechnologyStack() +  "&fragNo=" + fragmentNumber++ + "&len=" + byteCount + "&offset=" + offset;
						out.println(METHOD_NAME+": fragUrl = "+fragUrl);
					}
//					if(argMap.containsKey("scanPreferenceId"))
//					{
//						fragUrl += "&scanPreferenceId=" + argMap.get("scanPreferenceId");
//					}
//					if(argMap.containsKey("auditPreferenceId"))
//					{
//						fragUrl += "&auditPreferenceId=" + argMap.get("auditPreferenceId");
//					}
					Boolean runSonatypeScan = req.getRunSonatypeScan();

					if( null != runSonatypeScan )
					{
						if( runSonatypeScan )
						{
							fragUrl += "&doSonatypeScan=1";
						}
						else
						{
							fragUrl += "&doSonatypeScan=0";
						}
					}
					String postErrorMessage = "";
					out.println(METHOD_NAME+": calling sendPost ...");
					SendPostResponse postResponse = sendPost(fragUrl, sendByteArray, httpClient, sessionToken, postErrorMessage);
					HttpResponse response = postResponse.getResponse();
					if(response == null)
					{
						out.println(METHOD_NAME+": HttpResponse from sendPost is null!");
						status.setErrorMessage(postResponse.getErrorMessage());
						status.setSendPostFailed(true);
						break;
					}
					else
					{

						StatusLine sl = response.getStatusLine();
						Integer statusCode = Integer.valueOf(sl.getStatusCode()); 
						out.println(METHOD_NAME+": HttpResponse.StatusLine.statusCode = "+statusCode);
						out.println(METHOD_NAME+": HttpResponse.StatusLine.reasonPhrase = "+sl.getReasonPhrase());
						status.setHttpStatusCode(statusCode);
						if( !statusCode.toString().startsWith("2") )
						{
							status.setErrorMessage(sl.toString());
							//System.out.println("errorMessage="+errorMessage);
							break;
						}
						else
						{
							if(fragmentNumber != 0 && fragmentNumber % 5 == 0)
							{
								out.println(METHOD_NAME+": Upload Status - Bytes sent:" + offset);
								status.setBytesSent(offset);
							}
							if(lastFragment)
							{
								HttpEntity entity = response.getEntity();
								String finalResponse = EntityUtils.toString(entity).trim();
								out.println(METHOD_NAME+": finalResponse="+finalResponse);
								if(finalResponse.toUpperCase().equals("ACK") )
								{
									status.setUploadSucceeded(true);
									status.setBytesSent(offset);
								}
								else
								{
									status.setUploadSucceeded(false);
									status.setErrorMessage(finalResponse);
									status.setBytesSent(bytesSent);
								}
							}
						}
						EntityUtils.consume(response.getEntity());
					}
					out.println(METHOD_NAME+": byteCount="+byteCount);
					offset += byteCount;
				}
				bytesSent = offset;
				out.println(METHOD_NAME+": bytesSent="+bytesSent);
				fs.close();
			}
//			else
//			{
//				errorMessage = "Failed to authenticate";
//			}
		}
		else
		{
			status.setUploadSucceeded(false);
			status.setErrorMessage("Combination of applicationName of \""+req.getApplicationName()+"\" and releaseName of \""+req.getApplicationName()+"\" is not valid");
		}
		return status;
	}
	
	private SendPostResponse sendPost(String url, byte[] bytesToSend, HttpClient client, String token, String errorMessage)
	{
		final String METHOD_NAME = CLASS_NAME+".sendPost";
		
		PrintStream out = FodBuilder.getLogger();
		out.println(METHOD_NAME+": url="+url);
		out.println(METHOD_NAME+": token="+token);
		
		SendPostResponse result = new SendPostResponse();
		try {
			HttpPost httppost = new HttpPost(url);
			httppost.addHeader("Authorization","Bearer " + token);
			ByteArrayEntity entity = new ByteArrayEntity(bytesToSend);		
			httppost.setEntity(entity);
			HttpResponse response = client.execute(httppost);
			result.setResponse(response);
			result.setErrorMessage("");
		} catch (ParseException e) {
			e.printStackTrace();
			errorMessage = e.getMessage();
			result.setResponse(null);
			result.setErrorMessage(errorMessage);
		} catch (IOException e) {
			e.printStackTrace();
			errorMessage = e.getMessage();
			result.setResponse(null);
			result.setErrorMessage(errorMessage);
		} catch (Exception e) {
			e.printStackTrace();
			errorMessage = e.getMessage();
			result.setResponse(null);
			result.setErrorMessage(errorMessage);
		}
		return result;
	}

	public String sendData(String endpointData, byte[] data, long len,
			long frag, long offset)
	{
		final String METHOD_NAME = CLASS_NAME+".sendData";
		PrintStream out = FodBuilder.getLogger();
		
		HttpURLConnection connection = null;
		try {
			out.println(METHOD_NAME+": baseUrl="+baseUrl);
			String endpoint = baseUrl + endpointData 
					+ "&fragNo=" + frag
					+ "&offset=" + offset;
			URL url = new URL(endpoint);
			out.println(METHOD_NAME+": endpoint="+endpoint);

			out.println(METHOD_NAME+": proxy="+proxy);
			connection = getHttpUrlConnection("POST", url);
			connection.setRequestProperty("Content-Type","application/octet-stream");
			connection.setDoOutput(true);

			// Send request
			DataOutputStream wr = new DataOutputStream(
					connection.getOutputStream());
			wr.write(data, 0, (int) len);
			wr.flush();
			wr.close();

			// Get Response
			InputStream is = connection.getInputStream();
			StringBuffer response = collectInputStream(is);
			return response.toString();

		} catch (Exception e) {

			e.printStackTrace();
			return null;

		} finally {

			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	class SendPostResponse {

		private HttpResponse response;
		private String errorMessage;

		public HttpResponse getResponse() {
			return response;
		}

		public void setResponse(HttpResponse response) {
			this.response = response;
		}

		public String getErrorMessage() {
			return errorMessage;
		}

		public void setErrorMessage(String errorMessage) {
			this.errorMessage = errorMessage;
		}
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public Proxy getProxy() {
		return proxy;
	}

	public void setProxy(Proxy proxy) {
		InetSocketAddress address = ((InetSocketAddress)proxy.address());
		this.proxyHostname = address.getHostName();
		this.proxyPort = address.getPort();
	}
	
	public void setProxy(String hostname, int port) {
		this.proxyHostname = hostname;
		this.proxyPort = port;
		
		SocketAddress sa = new InetSocketAddress(hostname,port);
		Proxy proxy = new Proxy(Proxy.Type.HTTP,sa);
		this.proxy = proxy;
	}
	
	protected HttpURLConnection getHttpUrlConnection(String requestMethod, URL url) throws IOException
	{
		
		HttpURLConnection connection = null;
		
		if (proxy != null) {
			connection = (HttpURLConnection) url.openConnection(proxy);
		} else {
			connection = (HttpURLConnection) url.openConnection();
		}
		
		connection.setRequestMethod(requestMethod);
		connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");

		connection.setRequestProperty("Content-Language", "en-US");
		connection.setRequestProperty("Authorization", "Bearer " + sessionToken);

		connection.setUseCaches(false);
		connection.setDoInput(true);
		connection.setDoOutput(false);
		
		return connection;
	}

	protected StringBuffer collectInputStream(InputStream is)
			throws IOException
	{
		BufferedReader rd = new BufferedReader(new InputStreamReader(is));
		String line;
		StringBuffer response = new StringBuffer();
		while ((line = rd.readLine()) != null) {
			response.append(line);
			response.append('\r');
		}
		rd.close();
		return response;
	}

	protected JsonArray getDataJsonArray(StringBuffer response)
	{
		String responseString = response.toString();
		JsonParser parser = new JsonParser();
		JsonElement jsonElement = parser.parse(responseString);
		JsonObject dataObject = jsonElement.getAsJsonObject();
		JsonElement dataElement = dataObject.getAsJsonArray("data");
		JsonArray arr = dataElement.getAsJsonArray();
		return arr;
	}

	protected JsonElement getDataJsonElement(String responseString)
	{
		JsonParser parser = new JsonParser();
		JsonElement jsonElement = parser.parse(responseString);
		JsonObject responseObject = jsonElement.getAsJsonObject();
		JsonElement dataObject = responseObject.get("data");
		return dataObject;
	}
	
	protected Gson getGson()
	{
		
		if( null == gson )
		{
			GsonBuilder builder = new GsonBuilder();
			
			//2013-11-13T04:35:48.28
			//FIXME Gson creates dates assuming local time, but dates are probably UTC
			builder.setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SS");
			
			gson = builder.create();
		}
		return gson;
	}
}