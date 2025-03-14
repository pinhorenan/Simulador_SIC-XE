package sicxesimulator.application.controller;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.stage.Stage;
import sicxesimulator.application.util.DialogUtil;
import sicxesimulator.application.view.MainLayout;
import sicxesimulator.application.view.MainViewUpdater;
import sicxesimulator.models.ObjectFile;
import sicxesimulator.application.model.Model;
import sicxesimulator.application.model.ObjectFileTableItem;
import sicxesimulator.application.model.records.MemoryEntry;

import sicxesimulator.application.model.records.RegisterEntry;
import sicxesimulator.application.model.records.SymbolEntry;
import sicxesimulator.utils.*;
import sicxesimulator.utils.Map;

import java.io.*;
import java.util.*;

public class Controller {
    private final Model model;
    private final MainLayout mainLayout;
    private final MainViewUpdater updater;

    // Modo de ligação atual (recebido do MenuBarController)
    private MenuBarController.LinkerMode currentLinkerMode = MenuBarController.LinkerMode.RELOCAVEL;


    public Controller(Model model, MainLayout mainLayout) {
        this.model = model;
        this.mainLayout = mainLayout;
        this.updater = new MainViewUpdater(this, mainLayout);
        this.model.addListener(this::initializeFilesView);
    }

    /// ===== Inicialização da Tabela de Arquivos Montados ===== ///

    public void initializeFilesView() {
        List<ObjectFile> files = loadSavedObjectFiles();
        var objectFileTable = mainLayout.getObjectFilePanel().getObjectFileTable();

        if (!files.isEmpty()) {
            objectFileTable.getItems().clear();
            for (ObjectFile file : files) {
                objectFileTable.addEntry(new ObjectFileTableItem(file));
            }
        }
    }

    private List<ObjectFile> loadSavedObjectFiles() {
        List<ObjectFile> objectFiles = new ArrayList<>();
        File savedDir = new File(Constants.SAVE_DIR);

        if (savedDir.exists() && savedDir.isDirectory()) {
            // Carrega arquivos .meta (serializados)
            File[] metaFiles = savedDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".meta"));
            if (metaFiles != null) {
                for (File file : metaFiles) {
                    try {
                        ObjectFile objectFile = ObjectFile.loadFromFile(file); // desserializa
                        objectFiles.add(objectFile);
                    } catch (IOException e) {
                        DialogUtil.showError("Erro ao carregar arquivo meta: " + file.getName());
                    }
                }
            }
        }
        return objectFiles;
    }

    /// ===== Controles de Montagem ===== ///

    public void handleAssembleAction() {
        String rawSourceText = mainLayout.getInputPanel().getInputText();
        List<String> rawSourceLines = Arrays.asList(rawSourceText.split("\\r?\\n"));

        try {
            List<String> processedSourceLines = model.processCodeMacros(rawSourceLines);
            ObjectFile objectFile = model.assembleCode(rawSourceLines, processedSourceLines);

            mainLayout.getOutputPanel().clearOutput();
            mainLayout.getInputPanel().setInputText(rawSourceText);

            mainLayout.getOutputPanel().getOutputArea().appendText("Programa montado com sucesso!\n" + objectFile + "\n");

            initializeFilesView();

            SimulatorLogger.logAssemblyCode(rawSourceText);
            SimulatorLogger.logMachineCode(objectFile.toString());
        } catch (IllegalArgumentException | IOException e) {
            DialogUtil.showError("Erro na montagem: " + e.getMessage());
            SimulatorLogger.logError("Erro na montagem", e);
        }
    }

    /// ===== Controles da Tabela e Arquivos Montados ===== ///

    public void handleLinkSelectedFilesAction() {
        List<ObjectFile> selectedFiles = mainLayout.getObjectFilePanel().getObjectFileTable().getSelectedFiles();

        if (selectedFiles.size() <= 2) {
            DialogUtil.showError("Selecione pelo menos 2 módulos para ligar.");
            return;
        } // Essa checagem é "inútil", já que o botão é desabilitado caso não tenha pelo menos 2 módulos selecionados
        // Vou deixar aqui só por precaução/registro

        int loadAddr = 0;
        boolean fullReloc;

        if (currentLinkerMode == MenuBarController.LinkerMode.ABSOLUTO) {
            try {
                loadAddr = DialogUtil.askForInteger("Endereço de Carga", "Linkagem Absoluta", "Informe o endereço.");
                fullReloc = DialogUtil.askForBoolean("Relocação Final", "Aplicar relocação agora?");
            } catch (IOException e) {
                DialogUtil.showError("Operação cancelada ou inválida: " + e.getMessage());
                return;
            }
        } else {
            // Modo RELOCAVEL -> por padrão loadAddress = 0
            fullReloc = DialogUtil.askForBoolean("Relocação Final", "Aplicar relocação agora?");
        }


        // Invoca a lógica de linkagem, passando os parâmetros coletados
        ObjectFile linkedObject = model.linkObjectFiles(selectedFiles, loadAddr, fullReloc);

        DialogUtil.showInfoDialog("Arquivos Linkados",
                "Arquivos linkados com sucesso!",
                "O arquivo " + linkedObject.getProgramName() + " foi criado.");

        initializeFilesView();
    }


    public void handleDeleteSelectedFilesAction() {
        var objectFileTable = mainLayout.getObjectFilePanel().getObjectFileTable();
        List<ObjectFileTableItem> selectedItems = new ArrayList<>(objectFileTable.getSelectionModel().getSelectedItems());

        for (ObjectFileTableItem item : selectedItems) {
            ObjectFile objectFile = item.getObjectFile();
            model.removeAndDeleteObjectFileFromList(objectFile);
        }

        // Atualiza a tabela removendo os itens selecionados
        objectFileTable.getItems().removeAll(selectedItems);

        // Se a tabela ficou vazia, forçamos uma atualização manual
        if (objectFileTable.getItems().isEmpty()) {
            initializeFilesView();  // Isso garante que a tabela será atualizada corretamente.
        }
    }

    /// ===== Controles da Máquina ===== ///

    public void handleRunAction() {
        new Thread(() -> {
            while (!model.simulationFinishedProperty().get() && !model.simulationPausedProperty().get()) {
                try {
                    model.runNextInstruction();
                    Platform.runLater(() -> {
                        mainLayout.getOutputPanel().getOutputArea().appendText(model.getMachine().getControlUnit().getLastExecutionLog() + "\n");
                        updater.updateAllTables();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> DialogUtil.showError("Erro na execução: " + ex.getMessage()));
                    break;
                }
                model.applyCycleDelay();
            }
            Platform.runLater(() -> mainLayout.getOutputPanel().getOutputArea().appendText("Execução concluída!\n"));
        }).start();
    }

    public void handleNextAction() {
        if (model.codeLoadedProperty().get() && !model.simulationFinishedProperty().get()) {
            try {
                model.runNextInstruction();
                String log = model.getMachine().getControlUnit().getLastExecutionLog();
                mainLayout.getOutputPanel().getOutputArea().appendText(log + "\n");
                updater.updateAllTables();
                SimulatorLogger.logExecution(log);
                model.setSimulationFinished(model.getMachine().getControlUnit().isProcessorHalted());
            } catch (Exception e) {
                DialogUtil.showError("Erro na execução: " + e.getMessage());
                SimulatorLogger.logError("Erro na execução", e);
            }
        } else {
            DialogUtil.showError("Nenhum programa montado ou simulação já concluída!");
        }
    }

    public void handlePauseAction() {
        if (model.codeLoadedProperty().get()) {
            boolean isPaused = model.simulationPausedProperty().get();
            model.setSimulationPaused(!isPaused);

            String message = isPaused ? "Execução retomada!" : "Execução pausada!";
            mainLayout.getOutputPanel().getOutputArea().appendText(message + "\n");
            SimulatorLogger.logExecution(message);
        } else {
            DialogUtil.showError("Nenhum programa carregado para pausar!");
        }
    }

    public void handleRestartAction() {
        model.restartMachine();
        mainLayout.getOutputPanel().getOutputArea().clear();
        mainLayout.getOutputPanel().getOutputArea().appendText("Máquina reiniciada!\n");
        updateAllTables();
    }

    public void handleLoadObjectFileAction() {
        var table = mainLayout.getObjectFilePanel().getObjectFileTable();
        List<ObjectFileTableItem> selectedItems = new ArrayList<>(table.getSelectionModel().getSelectedItems());

        if (selectedItems.isEmpty()) {
            DialogUtil.showError("Nenhum arquivo selecionado para carregar.");
            return;
        }

        ObjectFile selectedFile = selectedItems.getFirst().getObjectFile();

        // Se ABSOLUTO, perguntar o endereço de carga.
        // Se RELOCAVEL, podemos assumir 0 (ou outro) e deixar a relocação para a lógica do loader.
        //noinspection UnusedAssignment
        int userLoadAddress = 0;
        if (!selectedFile.isFullyRelocated() && selectedFile.getStartAddress() != 0) {
            try {
                userLoadAddress = DialogUtil.askForInteger(
                        "Endereço de Carga",
                        "Carregador Absoluto",
                        "Digite o endereço onde carregar:"
                );
            } catch (IOException e) {
                throw new RuntimeException("Operação cancelada ou inválida.", e);
            }
        } else {
            // Se já for relocado ou o startAddress for 0, usamos o próprio startAddress.
            userLoadAddress = selectedFile.getStartAddress();
        }


        model.loadProgramToMachine(selectedFile, userLoadAddress);

        updateAllTables();
        mainLayout.getOutputPanel().getOutputArea().clear();
        mainLayout.getOutputPanel().getOutputArea().appendText(
                "Programa carregado com sucesso!\n" + selectedFile + "\n"
        );
    }

    /// ===== Métodos Getters ===== ///

    public Stage getStage() {
        return mainLayout.getRoot().getScene().getWindow() instanceof Stage ? (Stage) mainLayout.getRoot().getScene().getWindow() : null;
    }

    public Model getModel() {
        return model;
    }

    public String getAddressFormat() {
        return model.getViewConfig().getAddressFormat();
    }

    public int getMemorySize() {
        return model.getMemorySize();
    }

    public String getCycleDelay() {
        int simulationSpeed = model.getSimulationSpeed();
        return Map.simulationSpeedToCycleDelay(simulationSpeed)+ "ms";
    }

    public List<MemoryEntry> getMemoryEntries() {
       return model.getMemoryEntries();
    }

    public List<RegisterEntry> getRegisterEntries() {
        return model.getRegisterEntries();
    }

    public List<SymbolEntry> getSymbolEntries() {
        return model.getSymbolEntries();
    }

    public BooleanProperty getCodeLoadedProperty() {
        return model.codeLoadedProperty();
    }

    public BooleanProperty getSimulationFinishedProperty() {
        return model.simulationFinishedProperty();
    }

    /// ===== Controles de Configuração ===== ///

    public void loadInputField(String content) {
        mainLayout.getInputPanel().setInputText(content);
    }

    public List<String> getExpandedCode() throws IOException {
        return model.processCodeMacros(Arrays.asList(mainLayout.getInputPanel().getInputText().split("\\r?\\n")));
    }

    public byte[] getObjectFileBytes() {
        ObjectFile selectedFile = model.getLastLoadedCode();
        return (selectedFile != null) ? selectedFile.getObjectCode() : null;
    }

    public String getSuggestedFileName(String extension) {
        ObjectFile lastLoaded = model.getLastLoadedCode();
        return (lastLoaded != null) ? lastLoaded.getProgramName() + extension : "Programa" + extension;
    }

    public void clearMemory() {
        model.getMachine().getMemory().clearMemory();
        updater.updateMemoryTableView();
        mainLayout.getOutputPanel().getOutputArea().appendText("Memória limpa!\n");
        // Atualiza a label da bottom bar
        if (mainLayout.getBottomBarPanel() != null) {
            mainLayout.getBottomBarPanel().updateMemoryLabel();
        }
    }

    public void changeMemorySize(int newSizeInBytes) {
        try {
            model.getMachine().changeMemorySize(newSizeInBytes);
            model.setMemorySize(newSizeInBytes);
            mainLayout.getOutputPanel().getOutputArea().appendText("Memória alterada para " + newSizeInBytes + " bytes.\n");
            updater.updateMemoryTableView();
            if (mainLayout.getBottomBarPanel() != null) {
                mainLayout.getBottomBarPanel().updateMemoryLabel();
            }
        } catch (Exception e) {
            DialogUtil.showError("Erro ao alterar o tamanho da memória: " + e.getMessage());
        }
    }

    public void setSimulationSpeed(int speed) {
        model.setSimulationSpeed(speed);
        if (mainLayout.getBottomBarPanel() != null) {
            mainLayout.getBottomBarPanel().updateSpeedLabel();
        }
    }

    public void setViewFormat(String format) {
        model.getViewConfig().setAddressFormat(format);
        if (mainLayout.getBottomBarPanel() != null) {
            mainLayout.getBottomBarPanel().updateFormatLabel();
        }
    }

    public void setLinkerMode(MenuBarController.LinkerMode mode) {
        this.currentLinkerMode = mode;
        // Se quiser, você pode mostrar no console ou atualizar algo na interface
        System.out.println("Controller recebeu Modo de Ligação: " + mode);
    }

    public void showHelpWindow() {
        DialogUtil.showInfo("Aqui está a ajuda do simulador SIC/XE. (nenhuma, por enquanto, eu acho.)");
    }

    public void updateAllTables() {
        updater.updateAllTables();
    }

    public void updateAllLabels() {
        updater.updateAllLabels();
    }

    public MainLayout getMainLayout() {
        return mainLayout;
    }

    public MainViewUpdater getUpdater() {
        return updater;
    }
}