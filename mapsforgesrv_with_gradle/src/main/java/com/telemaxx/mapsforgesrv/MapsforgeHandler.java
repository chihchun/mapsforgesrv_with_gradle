package com.telemaxx.mapsforgesrv;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.mapelements.MapElementContainer;
import org.mapsforge.core.model.Tile;
import org.mapsforge.map.awt.graphics.AwtGraphicFactory;
import org.mapsforge.map.awt.graphics.AwtTileBitmap;
import org.mapsforge.map.datastore.MultiMapDataStore;
import org.mapsforge.map.layer.renderer.DatabaseRenderer;
import org.mapsforge.map.layer.renderer.RendererJob;
import org.mapsforge.map.layer.labels.TileBasedLabelStore;
import org.mapsforge.map.model.DisplayModel;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.ExternalRenderTheme;
import org.mapsforge.map.rendertheme.InternalRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback;
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleLayer;
import org.mapsforge.map.rendertheme.XmlRenderThemeStyleMenu;
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture;

public class MapsforgeHandler extends AbstractHandler {

	private static Logger LOG = Logger.getLogger(MapsforgeHandler.class);

	private final TreeSet<String> KNOWN_PARAMETER_NAMES = new TreeSet<>(
			Arrays.asList(new String[] { "x", "y", "z", "textScale", "userScale", "transparent", "tileRenderSize" }));

	protected final File mapFile;
	protected final File themeFile;
	protected final File themePropFile;
	protected final MultiMapDataStore multiMapDataStore;
	protected final DisplayModel displayModel;
	protected DatabaseRenderer renderer;
	protected XmlRenderTheme xmlRenderTheme;
	protected RenderThemeFuture renderThemeFuture;
	protected XmlRenderThemeStyleMenu renderThemeStyleMenu;
	protected TileBasedLabelStore tileBasedLabelStore = new MyTileBasedLabelStore(1000);
	protected DummyCache labelInfoCache = new DummyCache();

	private static final Pattern P = Pattern.compile("/(\\d+)/(\\d+)/(\\d+)\\.(.*)");

	public MapsforgeHandler(File mapFile, File themeFile) throws FileNotFoundException {
		super();
		this.mapFile = mapFile;
		this.themeFile = themeFile;
		if (themeFile != null) {
			themePropFile = new File(themeFile.getParentFile(), themeFile.getName() + ".prop");
		} else {
			themePropFile = null;
		}

		GraphicFactory graphicFactory = AwtGraphicFactory.INSTANCE;
		multiMapDataStore = new MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_FIRST);
		multiMapDataStore.addMapDataStore(new MapFile(mapFile), true, true);

		displayModel = new DisplayModel();

		renderer = new DatabaseRenderer(multiMapDataStore, graphicFactory, labelInfoCache, tileBasedLabelStore, true, true, null);
		renderThemeFuture = new RenderThemeFuture(graphicFactory, xmlRenderTheme, displayModel);
		XmlRenderThemeMenuCallback callBack = new XmlRenderThemeMenuCallback() {

			@Override
			public Set<String> getCategories(XmlRenderThemeStyleMenu styleMenu) {
				renderThemeStyleMenu = styleMenu;
				Properties prop = new Properties();
				if (themePropFile.isFile()) {
					try (FileReader r = new FileReader(themePropFile)) {
						prop.load(r);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				Set<String> result = new HashSet<>();
				for (Entry<String, XmlRenderThemeStyleLayer> entry : styleMenu.getLayers().entrySet()) {
					XmlRenderThemeStyleLayer overlay = entry.getValue();
					//System.out.println(overlay.getId() + " -> " + overlay.getTitle("en") + " enabled: " + overlay.isEnabled());
					//System.out.println("\t categories: " + Arrays.toString(entry.getValue().getCategories().toArray()));
					String propValue = prop.getProperty(overlay.getId());
					boolean overlayEnabled = overlay.isEnabled();
					if (propValue != null) {
						overlayEnabled = Boolean.parseBoolean(propValue);
					}
					prop.setProperty(overlay.getId(), Boolean.toString(overlayEnabled));
					if (overlayEnabled) {
						result.addAll(overlay.getCategories());
					}
				}

				try (FileWriter wr = new FileWriter(themePropFile)) {

					Properties tmp = new Properties() {
						@Override
						public synchronized Enumeration<Object> keys() {
							return Collections.enumeration(new TreeSet<Object>(super.keySet()));
						}
					};
					tmp.putAll(prop);
					tmp.store(wr, "MapsforgeSrv theme properties file");
					System.out.println("Saved enabled overlays to " + themePropFile);
				} catch (IOException e) {
					LOG.error("Failed to save MapsforgeSrv theme properties file", e);
				}
				return result;
			}

		};
		if (themeFile == null) {
			xmlRenderTheme = InternalRenderTheme.OSMARENDER;
		} else {
			xmlRenderTheme = new ExternalRenderTheme(themeFile, callBack);
		}

		updateRenderThemeFuture();
	}

	protected void updateRenderThemeFuture() {
		renderThemeFuture = new RenderThemeFuture(AwtGraphicFactory.INSTANCE, xmlRenderTheme, displayModel);
		new Thread(renderThemeFuture).start();
	}

	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {

		if (request.getPathInfo().equals("/favicon.ico")) {
			response.setStatus(404);
			return;
		}

		if (request.getPathInfo().equals("/updatemapstyle")) {
			updateRenderThemeFuture();
			try (ServletOutputStream out = response.getOutputStream();) {
				out.print("<html><body><h1>updatemapstyle</h1>OK</body></html>");
				out.flush();
			}
			response.setStatus(200);
			return;
		}

		response.setStatus(500);

		if (renderer == null || xmlRenderTheme == null)
			return;

		Enumeration<String> paramNames = request.getParameterNames();
		while (paramNames.hasMoreElements()) {
			String name = paramNames.nextElement();
			if (!KNOWN_PARAMETER_NAMES.contains(name)) {
				throw new ServletException("Unsupported query parameter: " + name);
			}
		}

		System.out.println("request: " + request);
		String path = request.getPathInfo();

		int x, y, z;
		String ext = "png";
		Matcher m = P.matcher(path);
		if (m.matches()) {
			x = Integer.parseInt(m.group(2));
			y = Integer.parseInt(m.group(3));
			z = Integer.parseInt(m.group(1));
			ext = m.group(4);
		} else {
			x = Integer.parseInt(request.getParameter("x"));
			y = Integer.parseInt(request.getParameter("y"));
			z = Integer.parseInt(request.getParameter("z"));
		}
		float textScale = 1.0f;
		try {
			String tmp = request.getParameter("textScale");
			if (tmp != null) {
				textScale = Float.parseFloat(tmp);
			} else {
				textScale = 1.0f;
			}
		} catch (Exception e) {
			throw new ServletException("Failed to parse \"textScale\" property: " + e.getMessage(), e);
		}

		float userScale = 1.0f;
		try {
			String tmp = request.getParameter("userScale");
			if (tmp != null) {
				userScale = Float.parseFloat(tmp);
			}
		} catch (Exception e) {
			throw new ServletException("Failed to parse \"userScale\" property: " + e.getMessage(), e);
		}
		displayModel.setUserScaleFactor(userScale);

		boolean transparent = false;
		try {
			String tmp = request.getParameter("transparent");
			if (tmp != null) {
				transparent = Boolean.parseBoolean(tmp);
			}
		} catch (Exception e) {
			throw new ServletException("Failed to parse \"transparent\" property: " + e.getMessage(), e);
		}

		int tileRenderSize = 256;
		try {
			String tmp = request.getParameter("tileRenderSize");
			if (tmp != null) {
				tileRenderSize = Integer.parseInt(tmp);
			}
		} catch (Exception e) {
			throw new ServletException("Failed to parse \"tileRenderSize\" property: " + e.getMessage(), e);
		}
		
		RendererJob job;
		Bitmap tileBitmap;
		Tile tile = new Tile(x, y, (byte) z, tileRenderSize);
		job = new RendererJob(tile, multiMapDataStore, renderThemeFuture, displayModel, textScale, transparent, false);
		synchronized (this) {
			tileBitmap = (AwtTileBitmap) renderer.executeJob(job);
			labelInfoCache.put(job, null);
		}
		BufferedImage image = AwtGraphicFactory.getBitmap(tileBitmap);

		if (image.getWidth() != 256) {
			final BufferedImage bufferedImage = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
			final Graphics2D graphics2D = bufferedImage.createGraphics();
			graphics2D.setComposite(AlphaComposite.Src);
			graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics2D.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			// graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics2D.drawImage(image, 0, 0, 256, 256, null);
			graphics2D.dispose();
			image = bufferedImage;
		}

		baseRequest.setHandled(true);
		response.setStatus(200);
		response.setContentType("image/" + ext);
		ImageIO.write(image, ext, response.getOutputStream());
	}

	private static class MyTileBasedLabelStore extends TileBasedLabelStore {

		public MyTileBasedLabelStore(int capacity) {
			super(capacity);
		}

		@Override
		public synchronized List<MapElementContainer> getVisibleItems(Tile upperLeft, Tile lowerRight) {
			return super.getVisibleItems(upperLeft, lowerRight);
		}

	}
	
}
