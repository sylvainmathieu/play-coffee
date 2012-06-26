package play.modules.coffee;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.jcoffeescript.JCoffeeScriptCompileException;
import org.jcoffeescript.JCoffeeScriptCompiler;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.exceptions.CompilationException;
import play.libs.IO;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.templates.Template;
import play.templates.TemplateLoader;
import play.vfs.VirtualFile;

/**
 * This plugin intercepts requests for static files ending in '.coffee', and
 * serves the compiled javascript instead.
 */
public class CoffeePlugin extends PlayPlugin {

    private static final class CompiledCoffee {
        public final Long sourceLastModified;  // Last modified time of the VirtualFile
        public final String output;  // Compiled coffee

        public CompiledCoffee(Long sourceLastModified, String output) {
            this.sourceLastModified = sourceLastModified;
            this.output = output;
        }
    }

    // Regex to get the line number of the failure.
    private static final Pattern LINE_NUMBER = Pattern.compile("line ([0-9]+)");
    private static final ThreadLocal<JCoffeeScriptCompiler> compiler =
        new ThreadLocal<JCoffeeScriptCompiler>() {
            @Override protected JCoffeeScriptCompiler initialValue() {
                return new JCoffeeScriptCompiler(); }};
    private Map<String, CompiledCoffee> cache;  // Map of Relative Path -> Compiled coffee

    /** @return the line number that the exception happened on, or 0 if not found in the message. */
    public static int getLineNumber(JCoffeeScriptCompileException e) {
        Matcher m = LINE_NUMBER.matcher(e.getMessage());
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    public static JCoffeeScriptCompiler getCompiler() {
        return compiler.get();
    }

	public static String minifyScript(String source) {
		String minified = "";
		String coffeeNativeFullpath = Play.configuration.getProperty("uglifyjs.path", "");
		if (!coffeeNativeFullpath.isEmpty()) {
			String[] command = { coffeeNativeFullpath };
			ProcessBuilder pb = new ProcessBuilder(command);
			Process minifyProcess = null;
			try {
				minifyProcess = pb.start();
				OutputStream os = minifyProcess.getOutputStream();
				os.write(source.getBytes());
				os.flush();
				os.close();
				BufferedReader minifyReader = new BufferedReader(new InputStreamReader(minifyProcess.getInputStream()));
				String line;
				while ((line = minifyReader.readLine()) != null) {
					minified += line + "\n";
				}
				String coffeeErrors = "";
				BufferedReader errorReader = new BufferedReader(new InputStreamReader(minifyProcess.getErrorStream()));
				while ((line = errorReader.readLine()) != null) {
					coffeeErrors += line + "\n";
				}
				if (!coffeeErrors.isEmpty()) {
					Logger.error("%s", coffeeErrors);
				}
				minifyReader.close();
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				if (minifyProcess != null) {
					minifyProcess.destroy();
				}
			}
		} else {
			minified = source;
		}
		return minified;
	}

    public static String compileCoffee(File coffeeFile) throws JCoffeeScriptCompileException {
        String compiledCoffee = "";
        String coffeeNativeFullpath = Play.configuration.getProperty("coffee.native", "");
        if (!coffeeNativeFullpath.isEmpty()) {
            String[] command = { coffeeNativeFullpath, "-p", coffeeFile.getAbsolutePath() };
            ProcessBuilder pb = new ProcessBuilder(command);
            Process coffeeProcess = null;
            try {
                coffeeProcess = pb.start();
                BufferedReader compiledReader = new BufferedReader(new InputStreamReader(coffeeProcess.getInputStream()));
                String line;
                while ((line = compiledReader.readLine()) != null) {
					compiledCoffee += line + "\n";
                }
				String coffeeErrors = "";
				BufferedReader errorReader = new BufferedReader(new InputStreamReader(coffeeProcess.getErrorStream()));
				while ((line = errorReader.readLine()) != null) {
					coffeeErrors += line + "\n";
				}
				if (!coffeeErrors.isEmpty()) {
					Logger.error("%s", coffeeErrors);
				}
                compiledReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (coffeeProcess != null) {
                    coffeeProcess.destroy();
                }
            }
        }
        else {
            compiledCoffee = getCompiler().compile(IO.readContentAsString(coffeeFile));
        }
		if (Play.mode.isProd()) {
			compiledCoffee = minifyScript(compiledCoffee);
		}
        return compiledCoffee;
    }

    @Override
    public void onLoad() {
        cache = new HashMap<String, CompiledCoffee>();

        if (Play.mode == Play.Mode.PROD) {
            Logger.info("Compiling coffee scripts...");
            String[] extensions = new String[] { "coffee" };
            int count = 0;
            List<File> coffeeFiles = (List<File>) FileUtils.listFiles(Play.getFile("public/javascripts"), extensions, true);
            for (File coffeeFile : coffeeFiles) {
                String relativePath = coffeeFile.getAbsolutePath().replace(Play.applicationPath.getAbsolutePath(), "");
                count++;
                try {
                    String compiledCoffee = compileCoffee(coffeeFile);
                    cache.put(relativePath, new CompiledCoffee(coffeeFile.lastModified(), compiledCoffee));
                } catch (JCoffeeScriptCompileException e) {
                    Logger.error("%s failed to compile.", coffeeFile.getAbsolutePath());
                }
                Logger.info("%s compiled.", relativePath);
            }
            Logger.info("Done. %d files compiled.", count);
        }
    }

    @Override
    public boolean serveStatic(VirtualFile file, Request request, Response response) {
        if (!file.getName().endsWith(".coffee")) {
            return super.serveStatic(file, request, response);
        }

        try {
            response.contentType = "text/javascript";
            response.status = 200;
            if (Play.mode == Play.Mode.PROD) {
                response.cacheFor("1h");
            }

            // Check the cache.
            String relativePath = file.relativePath();
            CompiledCoffee cc = cache.get(relativePath);
            if (cc != null && cc.sourceLastModified.equals(file.lastModified())) {
                response.print(cc.output);
                return true;
            }

            // Compile the coffee and return.
            String compiledCoffee = compileCoffee(file.getRealFile());
            cache.put(relativePath, new CompiledCoffee(file.lastModified(), compiledCoffee));
            response.print(compiledCoffee);

        } catch (JCoffeeScriptCompileException e) {
            // Render a nice error page.
            Template tmpl = TemplateLoader.load("errors/500.html");
            Map<String, Object> args = new HashMap<String, Object>();
            Exception ex = new CompilationException(file, e.getMessage(), getLineNumber(e), -1, -1);
            args.put("exception", ex);
            play.Logger.error(ex, "Coffee compilation error");
            response.contentType = "text/html";
            response.status = 500;
            response.print(tmpl.render(args));
        }

        return true;
    }
}