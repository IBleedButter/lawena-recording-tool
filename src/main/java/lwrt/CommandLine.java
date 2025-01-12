package lwrt;

import lwrt.SettingsManager.Key;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.List;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class lets you access to all values and actions that are OS-dependent, aiming to simplify the porting of some
 * features to all systems where TF2 can run (Windows, Linux and OSX)
 */
public abstract class CommandLine {

    /**
     * Main logger for the tool, by default it will write {@link Level#FINE} and higher events to file and in a simpler
     * way to the "Log" tab in the UI. Also it will write {@link Level#FINER} and higher events to console it it's
     * enabled.
     */
    static final Logger log = Logger.getLogger("lawena");

    static Set<PosixFilePermission> ALL_PERMISSIONS = new HashSet<>();

    static {
        ALL_PERMISSIONS.add(PosixFilePermission.OWNER_READ);
        ALL_PERMISSIONS.add(PosixFilePermission.OWNER_WRITE);
        ALL_PERMISSIONS.add(PosixFilePermission.OWNER_EXECUTE);
        ALL_PERMISSIONS.add(PosixFilePermission.GROUP_READ);
        ALL_PERMISSIONS.add(PosixFilePermission.GROUP_WRITE);
        ALL_PERMISSIONS.add(PosixFilePermission.GROUP_EXECUTE);
        ALL_PERMISSIONS.add(PosixFilePermission.OTHERS_READ);
        ALL_PERMISSIONS.add(PosixFilePermission.OTHERS_WRITE);
        ALL_PERMISSIONS.add(PosixFilePermission.OTHERS_EXECUTE);
    }

    /**
     * Returns the necessary {@link ProcessBuilder} to launch TF2. It will be used when {@link
     * #startTf(SettingsManager)} is called.
     *
     * @param gamePath a <code>String</code> equivalent to the game path.
     * @return The <code>ProcessBuilder</code> used to create a {@link Process} and launch TF2 with it or
     * <code>null</code> if it couldn't be created.
     */
    protected abstract ProcessBuilder getBuilderStartTF2(String gamePath);

    protected abstract ProcessBuilder getBuilderStartHL2(String gamePath);

    protected abstract ProcessBuilder getBuilderStartSteam(String steamPath);

    protected abstract ProcessBuilder getBuilderStartHLAE(String hlaePath, String gamePath);

    /**
     * Returns the necessary {@link ProcessBuilder} to stop or kill the TF2 process, to abort its execution. It will be
     * used when {@link #killTF2Process()} is called.
     *
     * @return The <code>ProcessBuilder</code> used to create a {@link Process} and kill the TF2 process or
     * <code>null</code> if it couldn't be created.
     */
    protected abstract ProcessBuilder getBuilderTF2ProcessKiller();

    protected abstract ProcessBuilder getBuilderHL2ProcessKiller();

    protected abstract ProcessBuilder getBuilderHLAEProcessKiller();

    /**
     * Returns the necessary {@link ProcessBuilder} to generate a preview of a VTF file, in particular in this tool, to
     * generate a skybox preview. It will be used when {@link #generatePreview(String)} is called.
     *
     * @param skyboxFilename the filename of the skybox file to generate the preview
     * @return The <code>ProcessBuilder</code> used to create a {@link Process} and generate the skybox preview or
     * <code>null</code> if it couldn't be created.
     */
    protected abstract ProcessBuilder getBuilderVTFCmd(String skyboxFilename);

    /**
     * Returns the {@link Path} of the Steam installation (where Steam.exe, steam.sh or equivalent is located)
     *
     * @return The <code>Path</code> where Steam and TF2 main executable, can be located.
     */
    public abstract Path getSteamPath();

    public abstract boolean isValidSteamPath(Path steamPath);

    /**
     * Returns the system DirectX level if it's stored somewhere in the filesystem.
     *
     * @return a <code>String</code> representing the dxlevel value in hexadecimal to use when launching TF2 or
     * <code>null</code> if it couldn't be retrieved.
     */
    public abstract String getSystemDxLevel();

    /**
     * Store the set DirectX level in the filesystem for future use, like with the {@link #getSystemDxLevel()} method.
     * If the operation is not supported this should be implemented as a no-op method or with a simple log message.
     *
     * @param dxlevel the user-specified DirectX level in hexadecimal
     */
    public abstract void setSystemDxLevel(String dxlevel);

    /**
     * Checks if the TF2 process is running.
     *
     * @return <code>true</code> if TF2 process still runs in the system or <code>false</code> if it does not.
     */
    public abstract boolean isRunningTF2();

    /**
     * Returns the {@link Path} where the VPK included with TF2 is located. This will be used to extract skyboxes for
     * preview generation and loading, and could be used for other features like packing, extracting, listing, etc.
     *
     * @param tfpath the path where the TF2 executable is located
     * @return The <code>Path</code> to the VPK tool resolved from the tfpath.
     */
    protected abstract Path resolveVpkToolPath(Path tfpath);

    /**
     * Closes all open handles matching this path. This is useful to unlock files that some process can hold preventing
     * the restore/replace file mechanism to work correctly.
     *
     * @param path the directory where handles are being closed
     */
    public abstract void closeHandles(Path path);

    /**
     * Deletes a file using OS-level commands.
     *
     * @param path the path to delete
     */
    public abstract void delete(Path path);

    /**
     * Extracts VPK-packed files to a specified path only if they not exist in the destination path.
     *
     * @param tfpath  The {@link Path} where TF2 main executable is located
     * @param vpkname The name of the VPK file to possibly extract the files
     * @param dest    The <code>Path</code> where the files will be extracted to
     * @param files   The filenames included in the VPK that might be extracted
     * @throws IOException If an error occurred while creating the destination folder
     * @deprecated VPK utility might crash so this method is not completely reliable.
     */
    @Deprecated
    public void extractIfNeeded(Path tfpath, String vpkname, Path dest, Iterable<String> files)
        throws IOException {
        List<String> fileList = new ArrayList<>();
        for (String file : files) {
            if (!Files.exists(dest.resolve(file))) {
                if (Files.exists(Paths.get(vpkname))) {
                    if (!Files.exists(dest) || !Files.isDirectory(dest)) {
                        Files.createDirectory(dest);
                    }
                    fileList.add(file);
                } else {
                    log.info("Required file was not found: " + file);
                }
            }
        }
        if (!fileList.isEmpty()) {
            extractVpkFile(tfpath, vpkname, dest, fileList);
        }
    }

    private void extractVpkFile(Path tfpath, String vpkname, Path dest, List<String> files) {
        List<String> cmds = new ArrayList<>();
        try {
            Path vpktool = resolveVpkToolPath(tfpath);
            cmds.add(vpktool.toString());
            cmds.add("x");
            cmds.add(Paths.get(vpkname).toAbsolutePath().toString());
            cmds.addAll(files);
            ProcessBuilder pb = new ProcessBuilder(cmds);
            pb.directory(dest.toFile());
            Process pr = pb.start();
            try (BufferedReader input = newProcessReader(pr)) {
                String line;
                while ((line = input.readLine()) != null) {
                    log.fine("[vpk] " + line);
                }
            }
            pr.waitFor();
        } catch (InterruptedException | IOException e) {
            log.info("Problem extracting contents from VPK file: " + vpkname);
        }
    }

    /**
     * Generate an preview image representing a specified skybox.
     *
     * @param skyboxFilename The filename of the skybox to generate the preview
     * @see #getBuilderVTFCmd(String)
     */
    public void generatePreview(String skyboxFilename) {
        try {
            ProcessBuilder pb = getBuilderVTFCmd(skyboxFilename);
            log.finer("generatePreview: " + pb.command());
            Process pr = pb.start();
            try (BufferedReader input = newProcessReader(pr)) {
                String line;
                while ((line = input.readLine()) != null) {
                    log.finer("[vtfcmd] " + line);
                }
            }
            pr.waitFor();
        } catch (InterruptedException | IOException e) {
            log.log(Level.INFO, "Problem while generating png from vtf file", e);
        }
    }

    /**
     * List the files of a specified VPK file.
     *
     * @param tfpath  The {@link Path} where TF2 main executable is located
     * @param vpkpath The <code>Path</code> where the VPK file to search is located
     * @return A {@link List} of <code>String</code>s of all the files inside the specified VPK file.
     */
    public List<String> getVpkContents(Path tfpath, Path vpkpath) {
        List<String> files = new ArrayList<>();
        try {
            Path vpktool = resolveVpkToolPath(tfpath);
            ProcessBuilder pb = new ProcessBuilder(vpktool.toString(), "l", vpkpath.toString());
            Process pr = pb.start();
            try (BufferedReader input = newProcessReader(pr)) {
                String line;
                while ((line = input.readLine()) != null) {
                    files.add(line);
                }
            }
            pr.waitFor();
            log.finer("[" + vpkpath.getFileName() + "] Contents scanned: " + files.size() + " file(s)");
        } catch (InterruptedException | IOException e) {
            log.info("Problem retrieving contents of VPK file: " + vpkpath);
        }
        return files;
    }

    /**
     * Stop or kill the TF2 process, whether it's being run from the tool or not.
     *
     * @see #getBuilderTF2ProcessKiller()
     */
    public void killTF2Process() {
        try {
            ProcessBuilder pb = getBuilderTF2ProcessKiller();
            Process pr = pb.start();
            try (BufferedReader input = newProcessReader(pr)) {
                String line;
                while ((line = input.readLine()) != null) {
                    log.fine("[TF2 Process Killer] " + line);
                }
            }
            pr.waitFor();
        } catch (InterruptedException | IOException e) {
            log.info("Problem stopping TF2 process");
        }
    }

    public void killHLAEProcess() {
        try {
            ProcessBuilder pb = getBuilderHLAEProcessKiller();
            Process pr = pb.start();
            try (BufferedReader input = newProcessReader(pr)) {
                String line;
                while ((line = input.readLine()) != null) {
                    log.fine("[HLAE Process Killer] " + line);
                }
            }
            pr.waitFor();
        } catch (InterruptedException | IOException e) {
            log.info("Problem stopping HLAE process");
        }
    }

    /**
     * Launch TF2 with some user-specified parameters.
     *
     * @param cfg the program settings from it will retrieve values like the dxlevel and the resolution
     * @see #getBuilderStartTF2(String)
     */
    public void startTf(SettingsManager cfg) {
        try {
            String mode = cfg.getString(Key.LaunchMode);
            String opts = cfg.getString(Key.LaunchOptions);
            Map<String, String> options = new LinkedHashMap<>();
            if (mode.equals("steam")) {
                // Launch using steam.exe
                options.put("-applaunch", "440");
            } else {
                if (mode.equals("hlae")) {
                    options.put("-insecure", "");
                }
                // Launch using tf_win64.exe
                options.put("-steam", "");
                options.put("-game", "tf");
            }
            // Additional options
            options.put("-dxlevel", cfg.getDxlevel());
            options.put("-w", cfg.getWidth() + "");
            options.put("-h", cfg.getHeight() + "");
            String[] params = opts.trim().replaceAll("\\s+", " ").split(" ");
            for (int i = 0; i < params.length; i++) {
                String key = params[i];
                String value = "";
                if (key.startsWith("-") || key.startsWith("+")) {
                    if (i + 1 < params.length) {
                        String next = params[i + 1];
                        if (next.matches("^-?\\d+$") || (!next.startsWith("-") && !next.startsWith("+"))) {
                            value = next;
                            i++;
                        }
                    }
                    options.put(key, value);
                } else {
                    log.warning("Discarding invalid launch parameter: " + key);
                }
            }
            // Remove -dxlevel if the user provided +mat_dxlevel
            // This won't have the desired effect if launch is done through Steam executable
            if (options.containsKey("+mat_dxlevel")) {
                options.remove("-dxlevel");
            }
            boolean fs = options.containsKey("-full") || options.containsKey("-fullscreen");
            if (fs) {
                options.remove("-sw");
                options.remove("-window");
                options.remove("-startwindowed");
                options.remove("-windowed");
            } else {
                options.put("-sw", "");
                options.put("-noborder", "");
            }
            if (options.containsKey("-width")) {
                options.remove("-w");
            }
            if (options.containsKey("-height")) {
                options.remove("-h");
            }
            // prepare list of options
            ProcessBuilder pb;
            switch (mode) {
                case "steam":
                    pb = getBuilderStartSteam(cfg.getString(Key.SteamDir));
                    pb.command().add("-applaunch");
                    pb.command().add(options.get("-applaunch"));
                    break;
                case "hlae":
                    pb = getBuilderStartHLAE(cfg.getString(Key.HlaePath), cfg.getString(Key.TfDir));
                    break;
                case "hl2":
                    pb = getBuilderStartHL2(cfg.getString(Key.TfDir));
                    break;
                default:
                    pb = getBuilderStartTF2(cfg.getString(Key.TfDir));
                    break;
            }
            options.remove("-applaunch");
            if (mode.equals("hlae")) {
                StringBuilder cmdLine = new StringBuilder("\"");
                for (Entry<String, String> e : options.entrySet()) {
                    cmdLine.append(e.getKey()).append(" ").append(e.getValue()).append(" ");
                }
                cmdLine.append("\"");
                pb.command().add(cmdLine.toString());
            } else {
                for (Entry<String, String> e : options.entrySet()) {
                    pb.command().add(e.getKey());
                    pb.command().add(e.getValue());
                }
            }
            // keeping this for compatibility
            if (cfg.getInsecure()) {
                log.fine("Using -insecure in launch options");
                pb.command().add("-insecure");
            }
            log.info("Launching: " + pb.command().toString()
                .replaceAll("[\\[\\],]", "")
                .replaceAll("\\s\\s+", " "));
            Process pr = pb.start();
            if (mode.equals("steam")) {
                try (BufferedReader input = newProcessReader(pr)) {
                    String line;
                    while ((line = input.readLine()) != null) {
                        log.finer("[steam] " + line);
                    }
                }
                pr.waitFor();
            }
        } catch (InterruptedException | IOException e) {
            log.warning("Process was interrupted: " + e);
        }
    }

    /**
     * Set the Look & Feel of the Graphical User Interface of the tool. By default it uses the system L&F.
     */
    public void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not set the look and feel", e);
        }
    }

    /**
     * Use the Java Desktop API to open a folder of the given path.
     *
     * @param path The folder that will be opened with the system file manager. If this is not a directory, it will
     *             open
     *             the file's parent.
     * @see Desktop#open(java.io.File)
     */
    void openFolder(Path path) {
        Path parent = path.getParent();
        Path dir = Files.isDirectory(path) ? path : (parent != null ? parent : path);
        try {
            Desktop.getDesktop().open(dir.toFile());
        } catch (IOException e) {
            log.log(Level.INFO, "Could not open directory: " + dir, e);
        }
    }

    public void open(Path path) {
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException e) {
            log.log(Level.INFO, "Could not open file: " + path, e);
        }
    }

    BufferedReader newProcessReader(Process p) {
        return new BufferedReader(new InputStreamReader(p.getInputStream(), Charset.forName("UTF-8")));
    }

    boolean processOutputHasLine(Process p) {
        try (BufferedReader input = newProcessReader(p)) {
            String line;
            line = input.readLine();
            if (line != null) {
                return true;
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not read process output", e);
        }
        return false;
    }

    /**
     * Register all .fon, .ttf, .ttc, or .otf files in the given path.
     *
     * @param path
     */
    public void registerFonts(Path path) {
        // no-op, used only in Windows
    }

}
