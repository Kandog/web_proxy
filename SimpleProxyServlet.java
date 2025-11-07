import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.*;
import java.util.*;

public class SimpleProxyServlet extends HttpServlet {

    private static class Mapping {
        String type;
        String path;
        String url;
    }

    private List<Mapping> mappings;

    @Override
    public void init() throws ServletException {
        mappings = new ArrayList<>();
        String baseUrl = getServletConfig().getInitParameter("baseUrl");

        for (int i = 1; ; i++) {
            String type = getServletConfig().getInitParameter("mapping." + i + ".type");
            String path = getServletConfig().getInitParameter("mapping." + i + ".path");

            if (type == null || path == null) {
                break;
            }

            Mapping mapping = new Mapping();
            mapping.type = type;
            mapping.path = path;

            if (!"block".equals(type)) {
                String url = getServletConfig().getInitParameter("mapping." + i + ".url");
                if (url == null) {
                    throw new ServletException("url init-param not configured for mapping " + i);
                }
                if (baseUrl != null && url.contains("${baseUrl}")) {
                    url = url.replace("${baseUrl}", baseUrl);
                }
                mapping.url = url;
            }

            mappings.add(mapping);
        }

    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String requestUri = req.getRequestURI().substring(req.getContextPath().length());
        String queryString = req.getQueryString();
        String fullRequestUrl = requestUri + (queryString != null ? "?" + queryString : "");

        String targetUrl = null;

        for (Mapping mapping : mappings) {
            if ("block".equals(mapping.type) && fullRequestUrl.startsWith(mapping.path)) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            } else if ("prefix".equals(mapping.type) && requestUri.startsWith(mapping.path)) {
                String restOfThePath = requestUri.substring(mapping.path.length());
                targetUrl = mapping.url + restOfThePath + (queryString != null ? "?" + queryString : "");
                break;
            } else if ("contains".equals(mapping.type) && fullRequestUrl.contains(mapping.path)) {
                targetUrl = mapping.url;
                if (queryString != null && !targetUrl.contains("?")) {
                    targetUrl += "?" + queryString;
                }
                break;
            }
        }

        if (targetUrl == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

		log("Proxying " + req.getMethod() + " request to: " + targetUrl);

		URL url = new URL(targetUrl);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setInstanceFollowRedirects(false);
		conn.setRequestMethod(req.getMethod());
		conn.setDoInput(true);
		conn.setDoOutput("POST".equalsIgnoreCase(req.getMethod()));

		// Forward headers (excluding Content-Length)
		Enumeration<String> headerNames = req.getHeaderNames();
		while (headerNames.hasMoreElements()) {
			String headerName = headerNames.nextElement();
			if (!headerName.equalsIgnoreCase("Content-Length")) {
				conn.setRequestProperty(headerName, req.getHeader(headerName));
			}
		}

		// Forward cookies
		Cookie[] cookies = req.getCookies();
		if (cookies != null) {
			StringBuilder cookieHeader = new StringBuilder();
			for (Cookie cookie : cookies) {
				cookieHeader.append(cookie.getName()).append("=").append(cookie.getValue()).append("; ");
			}
			conn.setRequestProperty("Cookie", cookieHeader.toString());
		}

		// Forward POST body
		if ("POST".equalsIgnoreCase(req.getMethod())) {
			try (InputStream in = req.getInputStream(); OutputStream out = conn.getOutputStream()) {
				byte[] buffer = new byte[8192];
				int len;
				while ((len = in.read(buffer)) != -1) {
					out.write(buffer, 0, len);
				}
				out.flush();
			}
		}

		int responseCode = conn.getResponseCode();
		log("Received response code: " + responseCode);
		resp.setStatus(responseCode);

		// Forward response headers (excluding Transfer-Encoding)
		String backendBaseUrl = getServletConfig().getInitParameter("baseUrl");
		Map<String, List<String>> responseHeaders = conn.getHeaderFields();
		for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
			String headerName = entry.getKey();
			if (headerName != null && !headerName.equalsIgnoreCase("Transfer-Encoding")) {
				if (headerName.equalsIgnoreCase("Location") && (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307 || responseCode == 308)) {
                    String location = entry.getValue().get(0);
                    if (location.startsWith(backendBaseUrl)) {
						String proxyBaseUrl = req.getScheme() + "://" + req.getServerName();
						if (req.getServerPort() != 80 && req.getServerPort() != 443) {
							proxyBaseUrl += ":" + req.getServerPort();
						}
						proxyBaseUrl += req.getContextPath();
                        String newLocation = location.replace(backendBaseUrl, proxyBaseUrl);
                        resp.addHeader(headerName, newLocation);
                    } else {
                        resp.addHeader(headerName, location);
                    }
                } else if (headerName.equalsIgnoreCase("Set-Cookie")) {
                    for (String cookie : entry.getValue()) {
                        String newCookie = cookie.replaceAll("(?i);\\s*Domain=[^;]*", "");
                        resp.addHeader(headerName, newCookie);
                    }
                } else {
					for (String value : entry.getValue()) {
						resp.addHeader(headerName, value);
					}
				}
			}
		}

		// Forward response body (handle error stream if needed)
		InputStream responseStream;
		try {
			responseStream = conn.getInputStream();
		} catch (IOException e) {
			responseStream = conn.getErrorStream();
		}

		if (responseStream != null) {
			try (InputStream in = responseStream; OutputStream out = resp.getOutputStream()) {
				byte[] buffer = new byte[8192];
				int len;
				while ((len = in.read(buffer)) != -1) {
					out.write(buffer, 0, len);
				}
				out.flush();
			}
		} else {
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "No response stream available");
		}
    }

}
