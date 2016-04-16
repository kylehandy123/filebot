package net.filebot.web;

import static java.nio.charset.StandardCharsets.*;
import static java.util.Arrays.*;
import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static net.filebot.CachedResource.fetchIfModified;
import static net.filebot.Logging.*;
import static net.filebot.util.JsonUtilities.*;
import static net.filebot.util.StringUtilities.*;
import static net.filebot.web.EpisodeUtilities.*;
import static net.filebot.web.WebRequest.*;

import java.io.Serializable;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import javax.swing.Icon;

import net.filebot.Cache;
import net.filebot.CacheType;
import net.filebot.ResourceManager;

public class TheTVDBClient2 extends AbstractEpisodeListProvider {

	private String apikey;

	public TheTVDBClient2(String apikey) {
		this.apikey = apikey;
	}

	@Override
	public String getName() {
		return "TheTVDB";
	}

	@Override
	public Icon getIcon() {
		return ResourceManager.getIcon("search.thetvdb");
	}

	@Override
	public boolean hasSeasonSupport() {
		return true;
	}

	protected Object requestJson(String path, Object post) throws Exception {
		// curl -X POST --header 'Content-Type: application/json' --header 'Accept: application/json' 'https://api.thetvdb.com/login' --data '{"apikey":"XXXXX"}'
		ByteBuffer response = post(getEndpoint(path), asJsonString(post).getBytes(UTF_8), "application/json", null);
		return readJson(UTF_8.decode(response));
	}

	protected Object requestJson(String path, Locale locale, Duration expirationTime) throws Exception {
		Cache cache = Cache.getCache(locale == null ? getName() : getName() + "_" + locale.getLanguage(), CacheType.Monthly);
		return cache.json(path, this::getEndpoint).fetch(fetchIfModified(() -> getRequestHeader(locale))).expire(expirationTime).get();
	}

	protected URL getEndpoint(String path) throws Exception {
		return new URL("https://api.thetvdb.com/" + path);
	}

	private Map<String, String> getRequestHeader(Locale locale) {
		Map<String, String> header = new LinkedHashMap<String, String>(3);
		if (locale != null) {
			header.put("Accept-Language", locale.getLanguage());
		}
		header.put("Accept", "application/json");
		header.put("Authorization", "Bearer " + getAuthorizationToken());
		return header;
	}

	private String token = null;

	private synchronized String getAuthorizationToken() {
		// curl -v -X GET --header 'Accept: application/json' --header 'Authorization: Bearer TOKEN' 'https://api.thetvdb.com/languages'
		if (token == null) {
			try {
				Object json = requestJson("login", singletonMap("apikey", apikey));
				token = getString(json, "token");
			} catch (Exception e) {
				throw new IllegalStateException("Failed to retrieve authorization token: " + e.getMessage(), e);
			}
		}
		return token;
	}

	protected String[] languages() throws Exception {
		Object response = requestJson("languages", Cache.ONE_MONTH);
		return streamJsonObjects(response, "data").map(it -> getString(it, "abbreviation")).toArray(String[]::new);
	}

	protected List<SearchResult> search(String path, Map<String, Object> query, Locale locale, Duration expirationTime) throws Exception {
		Object json = requestJson(path + "?" + encodeParameters(query, true), locale, expirationTime);

		return streamJsonObjects(json, "data").map(it -> {
			// e.g. aliases, banner, firstAired, id, network, overview, seriesName, status
			int id = getInteger(it, "id");
			String seriesName = getString(it, "seriesName");
			String[] aliasNames = stream(getArray(it, "aliases")).toArray(String[]::new);

			if (seriesName.startsWith("**") && seriesName.endsWith("**")) {
				debug.fine(format("Invalid series: %s [%d]", seriesName, id));
				return null;
			}

			return new SearchResult(id, seriesName, aliasNames);
		}).filter(Objects::nonNull).collect(toList());
	}

	@Override
	public List<SearchResult> fetchSearchResult(String query, Locale locale) throws Exception {
		return search("search/series", singletonMap("name", query), locale, Cache.ONE_DAY);
	}

	@Override
	public SeriesInfo getSeriesInfo(SearchResult series, Locale locale) throws Exception {
		Object json = requestJson("series/" + series.getId(), locale, Cache.ONE_WEEK);
		Object data = getMap(json, "data");

		SeriesInfo info = new SeriesInfo(this, locale, series.getId());
		info.setAliasNames(Stream.of(series.getAliasNames(), getArray(data, "aliases")).flatMap(it -> stream(it)).map(Object::toString).distinct().toArray(String[]::new));

		info.setName(getString(data, "seriesName"));
		info.setCertification(getString(data, "rating"));
		info.setNetwork(getString(data, "network"));
		info.setStatus(getString(data, "status"));

		info.setRating(getDecimal(data, "siteRating"));
		info.setRatingCount(getInteger(data, "siteRatingCount")); // TODO rating count not implemented in the new API yet

		info.setRuntime(matchInteger(getString(data, "runtime")));
		info.setGenres(stream(getArray(data, "genre")).map(Object::toString).collect(toList()));
		info.setStartDate(getStringValue(data, "firstAired", SimpleDate::parse));

		return info;
	}

	@Override
	protected SeriesData fetchSeriesData(SearchResult series, SortOrder sortOrder, Locale locale) throws Exception {
		// fetch series info
		SeriesInfo info = getSeriesInfo(series, locale);
		info.setOrder(sortOrder.name());

		// fetch episode data
		List<Episode> episodes = new ArrayList<Episode>();
		List<Episode> specials = new ArrayList<Episode>();

		for (int page = 1, lastPage = 1; page <= lastPage; page++) {
			Object json = requestJson("series/" + series.getId() + "/episodes?page=" + page, locale, Cache.ONE_DAY);
			lastPage = getInteger(getMap(json, "links"), "last");

			streamJsonObjects(json, "data").forEach(it -> {
				String episodeName = getString(it, "episodeName");
				Integer absoluteNumber = getInteger(it, "absoluteNumber");
				SimpleDate airdate = getStringValue(it, "firstAired", SimpleDate::parse);

				// default numbering
				Integer episodeNumber = getInteger(it, "airedEpisodeNumber");
				Integer seasonNumber = getInteger(it, "airedSeason");

				// use preferred numbering if possible
				if (sortOrder == SortOrder.DVD) {
					Integer dvdSeasonNumber = getInteger(it, "dvdSeason");
					Integer dvdEpisodeNumber = getInteger(it, "dvdEpisodeNumber");

					// require both values to be valid integer numbers
					if (dvdSeasonNumber != null && dvdEpisodeNumber != null) {
						seasonNumber = dvdSeasonNumber;
						episodeNumber = dvdEpisodeNumber;
					}
				} else if (sortOrder == SortOrder.Absolute && absoluteNumber != null && absoluteNumber > 0) {
					episodeNumber = absoluteNumber;
					seasonNumber = null;
				}

				if (seasonNumber == null || seasonNumber > 0) {
					// handle as normal episode
					episodes.add(new Episode(info.getName(), seasonNumber, episodeNumber, episodeName, absoluteNumber, null, airdate, new SeriesInfo(info)));
				} else {
					// handle as special episode
					specials.add(new Episode(info.getName(), null, null, episodeName, null, episodeNumber, airdate, new SeriesInfo(info)));
				}
			});
		}

		// episodes my not be ordered by DVD episode number
		episodes.sort(episodeComparator());

		// add specials at the end
		episodes.addAll(specials);

		return new SeriesData(info, episodes);
	}

	public SearchResult lookupByID(int id, Locale locale) throws Exception {
		if (id <= 0) {
			throw new IllegalArgumentException("Illegal TheTVDB ID: " + id);
		}

		SeriesInfo info = getSeriesInfo(new SearchResult(id, null), locale);
		return new SearchResult(id, info.getName(), info.getAliasNames());
	}

	public SearchResult lookupByIMDbID(int imdbid, Locale locale) throws Exception {
		if (imdbid <= 0) {
			throw new IllegalArgumentException("Illegal IMDbID ID: " + imdbid);
		}

		List<SearchResult> result = search("search/series", singletonMap("imdbId", imdbid), locale, Cache.ONE_MONTH);
		return result.size() > 0 ? result.get(0) : null;
	}

	@Override
	public URI getEpisodeListLink(SearchResult searchResult) {
		return URI.create("http://www.thetvdb.com/?tab=seasonall&id=" + searchResult.getId());
	}

	public List<Image> getImages(SearchResult series, String keyType) throws Exception {
		Object json = requestJson("series/" + series.getId() + "/images/query?keyType=" + keyType, null, Cache.ONE_WEEK);

		return streamJsonObjects(json, "data").map(it -> {
			Integer id = getInteger(it, "id");
			String subKey = getString(it, "subKey");
			String fileName = getString(it, "fileName");
			String resolution = getString(it, "resolution");
			Double rating = getDecimal(getString(it, "ratingsInfo"), "average");

			return new Image(id, keyType, subKey, fileName, resolution, rating);
		}).collect(toList());
	}

	public static class Image implements Serializable {

		private Integer id;
		private String keyType;
		private String subKey;
		private String fileName;
		private String resolution;
		private Double rating;

		protected Image() {
			// used by serializer
		}

		public Image(Integer id, String keyType, String subKey, String fileName, String resolution, Double rating) {
			this.id = id;
			this.keyType = keyType;
			this.subKey = subKey;
			this.fileName = fileName;
			this.resolution = resolution;
			this.rating = rating;
		}

		public Integer getId() {
			return id;
		}

		public String getKeyType() {
			return keyType;
		}

		public String getSubKey() {
			return subKey;
		}

		public String getFileName() {
			return fileName;
		}

		public String getResolution() {
			return resolution;
		}

		public Double getRating() {
			return rating;
		}

		@Override
		public String toString() {
			return "[id=" + id + ", keyType=" + keyType + ", subKey=" + subKey + ", fileName=" + fileName + ", resolution=" + resolution + ", rating=" + rating + "]";
		}

	}

}
