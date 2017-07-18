/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.comcast.cdn.traffic_control.traffic_router.core.ds;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

import com.comcast.cdn.traffic_control.traffic_router.core.util.JsonUtils;
import com.comcast.cdn.traffic_control.traffic_router.core.util.JsonUtilsException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.log4j.Logger;

import com.comcast.cdn.traffic_control.traffic_router.core.cache.Cache;
import com.comcast.cdn.traffic_control.traffic_router.core.cache.CacheLocation;
import com.comcast.cdn.traffic_control.traffic_router.core.cache.InetRecord;
import com.comcast.cdn.traffic_control.traffic_router.core.cache.Cache.DeliveryServiceReference;
import com.comcast.cdn.traffic_control.traffic_router.geolocation.Geolocation;
import com.comcast.cdn.traffic_control.traffic_router.core.request.DNSRequest;
import com.comcast.cdn.traffic_control.traffic_router.core.request.HTTPRequest;
import com.comcast.cdn.traffic_control.traffic_router.core.router.StatTracker.Track;
import com.comcast.cdn.traffic_control.traffic_router.core.router.StatTracker.Track.ResultType;
import com.comcast.cdn.traffic_control.traffic_router.core.router.StatTracker.Track.ResultDetails;
import com.comcast.cdn.traffic_control.traffic_router.core.util.StringProtector;

@SuppressWarnings({"PMD.TooManyFields","PMD.CyclomaticComplexity", "PMD.AvoidDuplicateLiterals"})
public class DeliveryService {
	protected static final Logger LOGGER = Logger.getLogger(DeliveryService.class);
	private final String id;
	@JsonIgnore
	private final JsonNode ttls;
	private final boolean coverageZoneOnly;
	@JsonIgnore
	private final JsonNode geoEnabled;
	private final String geoRedirectUrl;
	//store the url file path info
	private String geoRedirectFile;
	//check if the geoRedirectUrl belongs to this DeliveryService, avoid calculating this for multiple times
	//"INVALID_URL" for init status, "DS_URL" means that the request url belongs to this DeliveryService, "NOT_DS_URL" means that the request url doesn't belong to this DeliveryService
	private String geoRedirectUrlType;
	@JsonIgnore
	private final JsonNode staticDnsEntries;
	@JsonIgnore
	private final JsonNode domains;
	@JsonIgnore
	private final JsonNode bypassDestination;
	@JsonIgnore
	private final JsonNode soa;
	@JsonIgnore
	private final JsonNode props;
	private boolean isDns;
	private final String routingName;
	private final boolean shouldAppendQueryString;
	private final Geolocation missLocation;
	private final Dispersion dispersion;
	private final boolean ip6RoutingEnabled;
	private final Map<String, String> responseHeaders = new HashMap<String, String>();
	private final Set<String> requestHeaders = new HashSet<String>();
	private final boolean regionalGeoEnabled;
	private final String geolocationProvider;
	private final boolean anonymousIpEnabled;
	private final boolean sslEnabled;
	private static final int STANDARD_HTTP_PORT = 80;
	private static final int STANDARD_HTTPS_PORT = 443;
	private boolean hasX509Cert = false;
	private final boolean acceptHttp;
	private final boolean acceptHttps;
	private final boolean redirectToHttps;
	private final DeepCachingType deepCache;

	public enum DeepCachingType {
		NEVER,
		ALWAYS
	}

	public DeliveryService(final String id, final JsonNode dsJo) throws JsonUtilsException {
		this.id = id;
		this.props = dsJo;
		this.ttls = dsJo.get("ttls");

		if (this.ttls == null) {
			LOGGER.warn("ttls is null for:" + id);
		}

		this.coverageZoneOnly = JsonUtils.getBoolean(dsJo, "coverageZoneOnly");
		this.geoEnabled = dsJo.get("geoEnabled");
		String rurl = JsonUtils.optString(dsJo, "geoLimitRedirectURL", null);
		if (rurl != null && rurl.isEmpty()) { rurl = null; }
		this.geoRedirectUrl = rurl;
		this.geoRedirectUrlType = "INVALID_URL";
		this.geoRedirectFile = this.geoRedirectUrl;
		this.staticDnsEntries = dsJo.get("staticDnsEntries");
		this.bypassDestination = dsJo.get("bypassDestination");
		this.routingName = JsonUtils.getString(dsJo, "routingName").toLowerCase();
		this.domains = dsJo.get("domains");
		this.soa = dsJo.get("soa");
		this.shouldAppendQueryString = JsonUtils.optBoolean(dsJo, "appendQueryString", true);

		// missLocation: {lat: , long: }
		final JsonNode mlJo = dsJo.get("missLocation");
		if(mlJo != null) {
			final double lat = JsonUtils.optDouble(mlJo, "lat");
			final double longitude = JsonUtils.optDouble(mlJo, "long");
			missLocation = new Geolocation(lat, longitude);
		} else {
			missLocation = null;
		}

		this.dispersion = new Dispersion(dsJo);
		this.ip6RoutingEnabled = JsonUtils.optBoolean(dsJo, "ip6RoutingEnabled");
		setResponseHeaders(dsJo.get("responseHeaders"));
		setRequestHeaders(dsJo.get("requestHeaders"));
		this.regionalGeoEnabled = JsonUtils.optBoolean(dsJo, "regionalGeoBlocking");
		geolocationProvider = JsonUtils.optString(dsJo, "geolocationProvider");
		if (geolocationProvider != null && !geolocationProvider.isEmpty()) {
			LOGGER.info("DeliveryService '" + id + "' has configured geolocation provider '" + geolocationProvider + "'");
		} else {
			LOGGER.info("DeliveryService '" + id + "' will use default geolocation provider Maxmind");
		}
		sslEnabled = JsonUtils.optBoolean(dsJo, "sslEnabled");
		this.anonymousIpEnabled = JsonUtils.optBoolean(dsJo, "anonymousBlockingEnabled");

		final JsonNode protocol = dsJo.get("protocol");
		acceptHttp = JsonUtils.optBoolean(protocol, "acceptHttp", true);
		acceptHttps = JsonUtils.optBoolean(protocol, "acceptHttps");
		redirectToHttps = JsonUtils.optBoolean(protocol, "redirectToHttps");
		final String dctString = JsonUtils.optString(dsJo, "deepCachingType", "NEVER").toUpperCase();
		DeepCachingType dct = DeepCachingType.NEVER;
		try {
			dct = DeepCachingType.valueOf(dctString);
		} catch (IllegalArgumentException e) {
			LOGGER.error("DeliveryService '" + id + "' has an unrecognized deepCachingType: '" + dctString + "'. Defaulting to 'NEVER' instead");
		} finally {
			this.deepCache = dct;
		}
	}

	public String getId() {
		return id;
	}

	@JsonIgnore
	public JsonNode getTtls() {
		return ttls;
	}

	@Override
	public String toString() {
		return "DeliveryService [id=" + id + "]";
	}

	public Geolocation getMissLocation() {
		return missLocation;
	}

	public Geolocation supportLocation(final Geolocation clientLocation) {
		if (clientLocation == null) {
			if (missLocation == null) {
				return null;
			}

			return missLocation;
		}

		if (isLocationBlocked(clientLocation)) {
			return null;
		}

		return clientLocation;
	}

	private boolean isLocationBlocked(final Geolocation clientLocation) {
		if(geoEnabled == null || geoEnabled.size() == 0) { return false; }

		final Map<String, String> locData = clientLocation.getProperties();

		for (final JsonNode constraint : geoEnabled) {
			boolean match = true;
			try {
				final Iterator<String> keyIter = constraint.fieldNames();
				while (keyIter.hasNext()) {
					final String t = keyIter.next();
					final String v = JsonUtils.getString(constraint, t);
					final String data = locData.get(t);
					if (!v.equalsIgnoreCase(data)) {
						match = false;
						break;
					}
				}
				if (match) {
					return false;
				}
			} catch (JsonUtilsException ex) {
				LOGGER.warn(ex, ex);
			}
		}
		return true;
	}

	public boolean isCoverageZoneOnly() {
		return coverageZoneOnly;
	}

	public URL getFailureHttpResponse(final HTTPRequest request, final Track track) throws MalformedURLException {
		if(bypassDestination == null) {
			track.setResult(ResultType.MISS);
			track.setResultDetails(ResultDetails.DS_NO_BYPASS);
			return null;
		}
		track.setResult(ResultType.DS_REDIRECT);
		final JsonNode httpJo = bypassDestination.get("HTTP");
		if(httpJo == null) {
			track.setResult(ResultType.MISS);
			track.setResultDetails(ResultDetails.DS_NO_BYPASS);
			return null;
		}
		final JsonNode fqdn = httpJo.get("fqdn");
		if(fqdn == null) {
			track.setResult(ResultType.MISS);
			track.setResultDetails(ResultDetails.DS_NO_BYPASS);
			return null;
		}
		int port = request.isSecure() ? 443 : 80;
		if(httpJo.has("port")) {
			port = httpJo.get("port").asInt();
		}
		return new URL(createURIString(request, fqdn.asText(), port, null));
	}
	private static final String REGEX_PERIOD = "\\.";

	private boolean useSecure(final HTTPRequest request) {
		if (request.isSecure()) {
			return acceptHttps && isSslReady();
		}

		return redirectToHttps && acceptHttps && isSslReady();
	}

	private String getPortString(final HTTPRequest request, final int port) {
		final int standard_port = useSecure(request) ? STANDARD_HTTPS_PORT : STANDARD_HTTP_PORT;
		return port == standard_port ? "" : ":" + port;
	}

	private String getPortString(final HTTPRequest request, final Cache cache) {
		final int cache_port = useSecure(request) ? cache.getHttpsPort() : cache.getPort();
		return getPortString(request, cache_port);
	}

	public String createURIString(final HTTPRequest request, final Cache cache) {
		String fqdn = getFQDN(cache);
		if (fqdn == null) {
			final String[] cacheName = cache.getFqdn().split(REGEX_PERIOD, 2);
			fqdn = cacheName[0] + "." + request.getHostname().split(REGEX_PERIOD, 2)[1];
		}

		final int port = useSecure(request) ? cache.getHttpsPort() : cache.getPort();
		return createURIString(request, fqdn, port, getTransInfoStr(request));
	}

	private String createURIString(final HTTPRequest request, final String fqdn, final int port, final String tinfo) {
		final StringBuilder uri = new StringBuilder(useSecure(request) ? "https://" : "http://");

		uri.append(fqdn);
		uri.append(getPortString(request, port));
		uri.append(request.getUri());

		boolean queryAppended = false;
		if (request.getQueryString() != null && appendQueryString()) {
			uri.append('?').append(request.getQueryString());
			queryAppended = true;
		}
		if(tinfo != null) {
			if(queryAppended) {
				uri.append('&');
			} else {
				uri.append('?');
			}
			uri.append(tinfo);
		}
		return uri.toString();
	}

	public String createURIString(final HTTPRequest request, final String alternatePath, final Cache cache) {
		final StringBuilder uri = new StringBuilder(useSecure(request) ? "https://" : "http://");

		String fqdn = getFQDN(cache);
		if (fqdn == null) {
			final String[] cacheName = cache.getFqdn().split(REGEX_PERIOD, 2);
			fqdn = cacheName[0] + "." + request.getHostname().split(REGEX_PERIOD, 2)[1];
		}
		uri.append(fqdn);
		uri.append(getPortString(request, cache));
		uri.append(alternatePath);
		return uri.toString();
	}

	private String getFQDN(final Cache cache) {
		for (final DeliveryServiceReference dsRef : cache.getDeliveryServices()) {
			if (dsRef.getDeliveryServiceId().equals(this.getId())) {
				return dsRef.getFqdn();
			}
		}
		return null;
	}
	public List<InetRecord> getFailureDnsResponse(final DNSRequest request, final Track track) {
		if(bypassDestination == null) {
			track.setResult(ResultType.MISS);
			track.setResultDetails(ResultDetails.DS_NO_BYPASS);
			return null;
		}
		track.setResult(ResultType.DS_REDIRECT);
		track.setResultDetails(ResultDetails.DS_BYPASS);
		return getRedirectInetRecords(bypassDestination.get("DNS"));
	}

	private List<InetRecord> redirectInetRecords = null;

	@SuppressWarnings("PMD.CyclomaticComplexity")
	private List<InetRecord> getRedirectInetRecords(final JsonNode dns) {
		if (dns == null) {
			return null;
		}

		if (redirectInetRecords != null) {
			return redirectInetRecords;
		}

		try {
			synchronized (this) {
				final List<InetRecord> list = new ArrayList<InetRecord>();
				final int ttl = dns.get("ttl").asInt(); // we require a TTL to exist; will throw an exception if not present

				if (dns.has("ip") || dns.has("ip6")) {
					if (dns.has("ip")) {
						list.add(new InetRecord(InetAddress.getByName(dns.get("ip").asText()), ttl));
					}

					if (dns.has("ip6")) {
						String ipStr = dns.get("ip6").asText();

						if (ipStr != null && !ipStr.isEmpty()) {
							ipStr = ipStr.replaceAll("/.*", "");
							list.add(new InetRecord(InetAddress.getByName(ipStr), ttl));
						}
					}
				} else if (dns.has("cname")) {
					/*
					 * Per section 2.4 of RFC 1912 CNAMEs cannot coexist with other record types.
					 * As such, only add the CNAME if the above ip/ip6 keys do not exist
					 */
					final String cname = dns.get("cname").asText();

					if (cname != null) {
						list.add(new InetRecord(cname, ttl));
					}
				}

				this.redirectInetRecords = list;
			}
		} catch (Exception e) {
			redirectInetRecords = null;
			LOGGER.warn(e,e);
		}

		return redirectInetRecords;
	}

	@JsonIgnore
	public JsonNode getSoa() {
		return soa;
	}

	public boolean isDns() {
		return isDns;
	}
	public void setDns(final boolean isDns) {
		this.isDns = isDns;
	}

	public DeepCachingType getDeepCache() {
		return deepCache;
	}


	public boolean appendQueryString() {
		return shouldAppendQueryString;
	}

	enum TransInfoType {NONE, IP, IP_TID}

	public String getTransInfoStr(final HTTPRequest request) {
		final TransInfoType type = TransInfoType.valueOf(getProp("transInfoType", "NONE"));

		if (type == TransInfoType.NONE) {
			return null;
		}

		try {
			final byte[] ipBytes = getClientIpBytes(request, type);

			if (ipBytes == null) {
				return null;
			}

			return getEncryptedTrans(type, ipBytes);
		} catch (Exception e) {
			LOGGER.warn(e,e);
		}

		return null;
	}

	private byte[] getClientIpBytes(final HTTPRequest request, final TransInfoType type) throws UnknownHostException {
		final InetAddress ip = InetAddress.getByName(request.getClientIP());
		byte[] ipBytes = ip.getAddress();

		if (ipBytes.length > 4) {
			if (type == TransInfoType.IP) {
				return null;
			}

			ipBytes = new byte[]{0,0,0,0};
		}

		return ipBytes;
	}

	private String getEncryptedTrans(final TransInfoType type, final byte[] ipBytes) throws IOException, GeneralSecurityException {
		try (final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		     final DataOutputStream dos = new DataOutputStream(baos)) {

			dos.write(ipBytes);

			if (type == TransInfoType.IP_TID) {
				dos.writeLong(System.currentTimeMillis());
				dos.writeInt(getTid());
			}

			dos.flush();

			return "t0=" + getStringProtector().encryptForUrl(baos.toByteArray());
		}
	}

	private String getProp(final String key, final String d) {
		if(props == null || !props.has(key)) {
			return d;
		}
		return props.get(key).textValue();
	}
	private int getProp(final String key, final int d) {
		if(props == null || !props.has(key)) {
			return d;
		}
		return props.get(key).asInt();
	}

	static StringProtector stringProtector = null;
	private static StringProtector getStringProtector() {
		try {
			synchronized(LOGGER) {
				if(stringProtector == null) {
					stringProtector = new StringProtector("HajUsyac7"); // random passwd
				}
			}
		} catch (GeneralSecurityException e) {
			LOGGER.warn(e,e);
		}
		return stringProtector;
	}

	static AtomicInteger tid = new AtomicInteger(0);
	private static int getTid() {
		return tid.incrementAndGet();
	}

	private boolean isAvailable = true;
	private JsonNode disabledLocations;
	public void setState(final JsonNode state) {
		isAvailable = JsonUtils.optBoolean(state, "isAvailable", true);
		if(state != null) {
			// disabled locations
			disabledLocations = state.get("disabledLocations");
		}
	}

	public boolean isAvailable() {
		return isAvailable;
	}

	public boolean isLocationAvailable(final CacheLocation cl) {
		if(cl==null) {
			return false;
		}
		final JsonNode dls = this.disabledLocations;
		if(dls == null) {
			return true;
		}
		final String locStr = cl.getId();
		for (final JsonNode curr : dls) {
			if (locStr.equals(curr.asText())) {
				return false;
			}
		}

		return true;
	}

	public int getLocationLimit() {
		return getProp("locationFailoverLimit",0);
	}

	public int getMaxDnsIps() {
		return getProp("maxDnsIpsForLocation",0);
	}

	@JsonIgnore
	public JsonNode getStaticDnsEntries() {
		return staticDnsEntries;
	}

	@JsonIgnore
	public JsonNode getDomains() {
		return domains;
	}

	public String getRoutingName() {
		return routingName;
	}

	public Dispersion getDispersion() {
		return dispersion;
	}

	public String getGeoRedirectUrl() {
		return geoRedirectUrl;
	}

	public String getGeoRedirectUrlType() {
		return this.geoRedirectUrlType;
	}

	public void setGeoRedirectUrlType(final String type) {
		this.geoRedirectUrlType = type;
	}

	public String getGeoRedirectFile() {
		return this.geoRedirectFile;
	}

	public void setGeoRedirectFile(final String filePath) {
		this.geoRedirectFile = filePath;
	}

	public boolean isIp6RoutingEnabled() {
		return ip6RoutingEnabled;
	}

	public Map<String, String> getResponseHeaders() {
		return responseHeaders;
	}

	private void setResponseHeaders(final JsonNode jo) throws JsonUtilsException {
		if (jo != null) {
			final Iterator<String> keyIter = jo.fieldNames();
			while (keyIter.hasNext()) {
				final String key = keyIter.next();
				responseHeaders.put(key, JsonUtils.getString(jo, key));
			}
		}
	}

	public Set<String> getRequestHeaders() {
		return requestHeaders;
	}

	private void setRequestHeaders(final JsonNode jsonRequestHeaderNames) {
		if (jsonRequestHeaderNames == null) {
			return;
		}
		for (final JsonNode name : jsonRequestHeaderNames) {
			requestHeaders.add(name.asText());
		}
	}

	public boolean isRegionalGeoEnabled() {
		return regionalGeoEnabled;
	}

	public String getGeolocationProvider() {
		return geolocationProvider;
	}

	public boolean isAnonymousIpEnabled() {
		return anonymousIpEnabled;
	}

	public List<CacheLocation> filterAvailableLocations(final Collection<CacheLocation> cacheLocations) {
		final List<CacheLocation> locations = new ArrayList<CacheLocation>();

		for (final CacheLocation cl : cacheLocations) {
			if (isLocationAvailable(cl)) {
				locations.add(cl);
			}
		}

		return locations;
	}

	public boolean isSslEnabled() {
		return sslEnabled;
	}

	public void setHasX509Cert(final boolean hasX509Cert) {
		this.hasX509Cert = hasX509Cert;
	}

	public boolean isSslReady() {
		return sslEnabled && hasX509Cert;
	}

	public boolean isAcceptHttp() {
		return acceptHttp;
	}
}
