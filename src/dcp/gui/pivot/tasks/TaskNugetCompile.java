package dcp.gui.pivot.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;

import javax.xml.stream.XMLStreamException;

import org.apache.pivot.collections.List;
import org.apache.pivot.util.concurrent.Task;
import org.apache.pivot.wtk.Component;

import dcp.config.compile.NugetProcessCompiler;
import dcp.config.io.IOFactory;
import dcp.config.io.ps.chocolatey.InstallWriter;
import dcp.config.io.xml.nuget.ConfigWriter;
import dcp.config.io.xml.nuget.SpecWriter;
import dcp.gui.pivot.Master;
import dcp.logic.factory.PackFactory;
import dcp.logic.factory.TypeFactory.FILE_TYPE;
import dcp.logic.model.Pack;
import dcp.logic.model.config.SetupConfig;
import dcp.main.log.Out;

/**
 * 
 * @author SSAIDELI
 *
 */
public class TaskNugetCompile extends Task<Boolean>
{
    private NugetProcessCompiler compiler = new NugetProcessCompiler();// NuGet process compiler
    private String feedUrl;// nuget feed http url
    private int stepNbr;// step nbr (1.Config - 2.Spec - 3.Pack - 4.Push)
    
    private List<Pack> packs = PackFactory.getPacks();
    private SetupConfig setupConfig = Master.facade.setupConfig;
    
    public TaskNugetCompile(String targetPath, String feedUrl, int stepNbr) {
        this.compiler.setTarget(targetPath);
        this.feedUrl = feedUrl;
        this.stepNbr = stepNbr;
    }
    
    // Set log component to display compile stream
    public void setLogger(Component LOGGER) {
        Out.setLogger(LOGGER);
    }
    
    
    @Override
    public Boolean execute()
    {
        try {
            Out.print("BUILD", "Compiling package to " + compiler.getTarget());

            if (stepNbr > 0 && !config())
                return false;
            if (stepNbr > 1 && !writeSpecs())
                return false;
            if (stepNbr > 2 && (!pack() || !clean()) )
                return false;
            if (stepNbr > 3 && !push())
                return false;
            
            Out.print("BUILD", "Compilation success.");
            
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        } catch (XMLStreamException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        return false;
    }
    
    /**
     * Write a config file containing a list of all packages to install
     * Used for Chocolatey install command ($ cinst packages.config)
     * @return boolean: success
     * @throws XMLStreamException
     */
    private boolean config() throws XMLStreamException {
        Out.print("BUILD", "Adding chocolatey config file for install..");
        
        ConfigWriter CW = new ConfigWriter(new File(compiler.getTarget(), "packages.config").toString(), this.feedUrl);
        CW.writePackages(packs);
        CW.close();
        
        return true;
    }
    
    /**
     * Writes Specs (.nuspec) files for packs
     * @return boolean: success
     * @throws XMLStreamException
     * @throws IOException 
     */
    private boolean writeSpecs() throws XMLStreamException, IOException, FileAlreadyExistsException {
        Out.print("BUILD", "Writing NuSpec files..");
        SpecWriter SW;
        InstallWriter InstW = new InstallWriter(IOFactory.psChocolateyInstall);
        InstallWriter UninstW = new InstallWriter(IOFactory.psChocolateyUninstall);
        
        File repo, tools,
            nuspec, install, uninstall;
        for(Pack pack:packs) {
            if (pack.isHidden()) continue; // discard hidden packs (included in packs depending on them)
            
            // file pointers
            repo = new File(compiler.getTarget(), pack.getInstallName());
            tools = new File(repo.toString(), "tools");
            nuspec = new File(repo.toString(), pack.getInstallName()+".nuspec");
            install = new File(tools.toString(), "chocolateyInstall.ps1");
            uninstall = new File(tools.toString(), "chocolateyUninstall.ps1");
            
            // files cleaning if override
            if (!repo.exists()) repo.mkdir();
            else if (!pack.isOverride()) continue;
            else if (nuspec.exists()) Files.delete(nuspec.toPath());
            if (!tools.exists()) tools.mkdir();
            else if (install.exists()) Files.delete(install.toPath());
            
            // nuspec writing
            SW = new SpecWriter(nuspec.toString());
            SW.writeMetadata(pack);
            SW.close();
            
            // powershell files generate
            //Files.copy(new File(IOFactory.psChocolateyInstall).toPath(), install.toPath()); // install script
            InstW.fillFrom(setupConfig.getInstallPath(), pack);
            InstW.writeTo(install);
            //Files.copy(new File(IOFactory.psChocolateyUninstall).toPath(), uninstall.toPath()); // uninstall script
            UninstW.fillFrom(setupConfig.getInstallPath(), pack);
            UninstW.writeTo(uninstall);
            
            deployOptionPrepare(repo, pack);
            if (pack.getPackDependency() != null && pack.getPackDependency().isHidden())
                deployOptionPrepare(repo, pack.getPackDependency());
            else if (pack.getGroupDependency() != null) {
                for(Pack p:packs)
                    if (p.getGroup() != null && p.isHidden() &&
                        ( p.getGroup() == pack.getGroupDependency() || p.getGroup().hasParent(pack.getGroupDependency()) ))
                        deployOptionPrepare(repo, p);
            }
        }
        
        return true;
    }
    
    /**
     * Adds files to folder depending on deploy type
     * @param repo
     * @param p
     * @throws IOException
     */
    private void deployOptionPrepare(File repo, Pack p) throws IOException
    {
        File copyFolder, extrFolder, execFolder;
        
        switch (p.getInstallType()) {
        
        case DEFAULT:
        case COPY:
            copyFolder = new File(repo.toString(), "copy");
            copyFolder.mkdir();
            Files.copy(new File(p.getPath()).toPath(), new File(copyFolder, p.getName()).toPath());
            Out.print("DEBUG", "copy "+ p.getPath() + " to " + new File(copyFolder, p.getName()).toString());
            break;
            
        case EXTRACT:
            extrFolder = new File(repo.toString(), "extr");
            extrFolder.mkdir();
            Files.copy(new File(p.getPath()).toPath(), new File(extrFolder, p.getName()).toPath());
            Out.print("DEBUG", "copy "+ p.getPath() + " to " + new File(extrFolder, p.getName()).toString());
            break;
            
        case EXECUTE:
            execFolder = new File(repo.toString(), "exec");
            execFolder.mkdir();
            Files.copy(new File(p.getPath()).toPath(), new File(execFolder, p.getName()).toPath());
            
            if (p.getFileType() == FILE_TYPE.Executable) // create new empty file flag to ignore batch redirection to executable
                new File(execFolder, p.getName()+".ignore").createNewFile();
            
            Out.print("DEBUG", "copy "+ p.getPath() + " to " + new File(execFolder, p.getName()).toString());
            break;
            
        default:
            break;
        }
    }

    //nuget pack oracle/oracle.nuspec -NoPackageAnalysis
    /**
     * Pack nuspec files to nupkg packages
     * @return boolean: success
     * @throws IOException
     * @throws InterruptedException 
     */
    private boolean pack() throws IOException, InterruptedException {
        Out.print("BUILD", "Packing NuSpec files to NuPkg..");
        boolean success = true;
        int errCode = 0;
        
        File pkg, nuspec;
        for (Pack p:packs) {
            if (p.isHidden()) continue; // discard hidden packs (included in packs depending on them)
            
            pkg = new File(compiler.getTarget(), p.getInstallName() + "." + p.getInstallVersion() + ".nupkg");
            nuspec = new File(compiler.getTarget(), new File(p.getInstallName(), p.getInstallName()+".nuspec").toString());
            Out.print("DEBUG", "Packing spec file "+ nuspec.toString() + " to package " + pkg.toString());
            
            if (!p.isOverride() && pkg.exists())
                continue;
            
            errCode = compiler.pack(nuspec).waitFor();
            if (errCode != 0) {
                Out.print("ERROR", "Packing package " + pkg.toString() + " exited with code " + errCode);
                success = false;
            }
        }
        
        return success;
    }
    
    /**
     * Delete source folders containing spec files
     * @return boolean: success
     * @throws IOException
     */
    private boolean clean() throws IOException {
        Out.print("BUILD", "Cleaning Specification folders..");
        
        File file;
        String path;
        for(Pack p:packs) {
            file = new File(compiler.getTarget(), p.getInstallName());
            if (file.exists()) {
                path = file.toString();
                Out.print("DEBUG", "Deleting file " + path);
                compiler.cleanDir(path);
            }
        }
        
        return true;
    }
    
    /**
     * Push (send) packaged packs to nuget feed
     * @return boolean: success
     * @throws IOException 
     * @throws InterruptedException 
     */
    private boolean push() throws InterruptedException, IOException {
        Out.print("BUILD", "Sending packages to nuget feed " + feedUrl);
        boolean success = true;
        int errCode = 0;
        
        File pkg;
        for(Pack p:packs) {
            pkg = new File(compiler.getTarget(), p.getInstallName() + "." + p.getInstallVersion() + ".nupkg");
            if (!p.isHidden() && pkg.exists()) {
                Out.print("DEBUG", "Sending file " + pkg.toString());
                
                errCode = compiler.push(pkg, feedUrl).waitFor();
                if (errCode != 0) {
                    Out.print("ERROR", "Pushing package " + pkg.toString() + " exited with code " + errCode);
                    success = false;
                }
            }
        }
        
        return success;
    }

}
