package com.satya.trading;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.Styles;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public class TradingApp extends Application {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.of("America/New_York"));
    private static final java.time.Duration REFRESH = java.time.Duration.ofSeconds(5);

    private static final String MONO = "-fx-font-family: 'JetBrains Mono', 'Cascadia Mono', Consolas, monospace;";

    private static AlpacaClient client;
    private static StrategyEngine engine;

    public static void main(String[] args) {
        client = AlpacaClient.paperFromEnv();
        Path stateFile = Path.of(".allocations.json");
        engine = new StrategyEngine(client, stateFile);

        Thread t = new Thread(() -> {
            try {
                engine.run();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "strategy-engine");
        t.setDaemon(true);
        t.start();

        Application.launch(TradingApp.class, args);
    }

    private Stage stage;
    private Circle marketDot;
    private Label marketLabel;
    private Label clockLabel;
    private Label updatedLabel;
    private Label equityVal;
    private Label cashVal;
    private Label buyingPowerVal;
    private Label plVal;

    private volatile List<Position> latestPositions = List.of();

    private final ObservableList<PositionRow> positionRows = FXCollections.observableArrayList();
    private final ObservableList<StrategyRow> strategyRows = FXCollections.observableArrayList();
    private final FilteredList<StrategyRow> filteredStrategyRows = new FilteredList<>(strategyRows, r -> true);
    private final ObservableList<OrderRow> orderRows = FXCollections.observableArrayList();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(20, 24, 20, 24));

        VBox top = new VBox(20, buildHeader(), buildStatsRow());
        root.setTop(top);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Positions", wrap(buildPositionsTable())));
        tabs.getTabs().add(new Tab("Strategies", buildStrategiesTab()));
        tabs.getTabs().add(new Tab("Open Strategies", wrap(buildOrdersTable())));
        tabs.getTabs().add(new Tab("Place Order", buildPlaceOrderTab()));
        BorderPane.setMargin(tabs, new Insets(20, 0, 0, 0));
        root.setCenter(tabs);

        Scene scene = new Scene(root, 1280, 860);
        stage.setTitle("Trading Console");
        stage.setScene(scene);
        stage.show();

        startPolling();
    }

    private static VBox wrap(Node content) {
        VBox v = new VBox(content);
        v.setPadding(new Insets(16, 4, 4, 4));
        VBox.setVgrow(content, Priority.ALWAYS);
        return v;
    }

    private HBox buildHeader() {
        Label title = new Label("TRADING CONSOLE");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: 800;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        marketDot = new Circle(6, Color.GRAY);
        marketLabel = new Label("Market: —");
        marketLabel.setStyle("-fx-font-weight: 600;");
        HBox marketBox = new HBox(8, marketDot, marketLabel);
        marketBox.setAlignment(Pos.CENTER_LEFT);

        clockLabel = new Label("--:--:--");
        clockLabel.getStyleClass().add(Styles.TEXT_MUTED);
        clockLabel.setStyle(MONO);

        updatedLabel = new Label("Connecting…");
        updatedLabel.getStyleClass().add(Styles.TEXT_SUBTLE);

        HBox right = new HBox(24, marketBox, clockLabel, updatedLabel);
        right.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(20, title, spacer, right);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 14, 0));
        return header;
    }

    private HBox buildStatsRow() {
        equityVal = bigStatValue();
        cashVal = bigStatValue();
        buyingPowerVal = bigStatValue();
        plVal = bigStatValue();

        HBox row = new HBox(16,
                statCard("EQUITY", equityVal),
                statCard("CASH", cashVal),
                statCard("BUYING POWER", buyingPowerVal),
                statCard("UNREALIZED P&L", plVal));
        for (Node n : row.getChildren())
            HBox.setHgrow(n, Priority.ALWAYS);
        return row;
    }

    private static VBox statCard(String label, Label value) {
        Label l = new Label(label);
        l.getStyleClass().add(Styles.TEXT_MUTED);
        l.setStyle("-fx-font-size: 10px; -fx-font-weight: 700;");
        VBox card = new VBox(10, l, value);
        card.setPadding(new Insets(18, 22, 18, 22));
        card.setStyle(
                "-fx-background-color: -color-bg-subtle;"
                        + "-fx-background-radius: 10;"
                        + "-fx-border-color: -color-border-default;"
                        + "-fx-border-radius: 10;"
                        + "-fx-border-width: 1;");
        return card;
    }

    private static Label bigStatValue() {
        Label l = new Label("—");
        l.setStyle("-fx-font-size: 22px; -fx-font-weight: 700;" + MONO);
        return l;
    }

    private VBox buildStrategiesTab() {
        Label selectorLabel = new Label("STRATEGY");
        selectorLabel.getStyleClass().add(Styles.TEXT_MUTED);
        selectorLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: 700;");

        ComboBox<String> selector = new ComboBox<>();
        selector.getItems().addAll("All", "Trailing Stop", "Do Nothing");
        selector.setValue("All");
        selector.setPrefWidth(220);
        selector.valueProperty().addListener((obs, old, val) -> {
            if (val == null || "All".equals(val)) {
                filteredStrategyRows.setPredicate(r -> true);
            } else {
                filteredStrategyRows.setPredicate(r -> val.equals(r.strategy()));
            }
        });

        Button addBtn = new Button("+ Add Allocation");
        addBtn.getStyleClass().add(Styles.ACCENT);
        addBtn.setOnAction(e -> openAddAllocationDialog());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(12, selectorLabel, selector, spacer, addBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox paramsCard = new VBox(6,
                paramLine("Trailing Stop", "drop 10% / ratchet 3% / stop GTC sell"),
                paramLine("Do Nothing", "hold shares passively, no orders placed"));
        paramsCard.setPadding(new Insets(12, 16, 12, 16));
        paramsCard.setStyle(
                "-fx-background-color: -color-bg-subtle;"
                        + "-fx-background-radius: 8;"
                        + "-fx-border-color: -color-border-default;"
                        + "-fx-border-radius: 8;"
                        + "-fx-border-width: 1;");

        TableView<StrategyRow> table = buildStrategiesTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox tab = new VBox(16, toolbar, paramsCard, table);
        tab.setPadding(new Insets(16, 4, 4, 4));
        return tab;
    }

    private static HBox paramLine(String key, String val) {
        Label k = new Label(key);
        k.getStyleClass().add(Styles.TEXT_MUTED);
        k.setMinWidth(120);
        Label v = new Label(val);
        v.setStyle(MONO);
        HBox h = new HBox(8, k, v);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private VBox buildPlaceOrderTab() {
        TextField symbolFld = new TextField();
        symbolFld.setPromptText("AAPL, BTCUSD, SPY, ...");
        symbolFld.setTextFormatter(new TextFormatter<>(change -> {
            change.setText(change.getText().toUpperCase(Locale.US));
            change.setRange(change.getRangeStart(), change.getRangeEnd());
            return change;
        }));

        ComboBox<String> sideFld = new ComboBox<>();
        sideFld.getItems().addAll("buy", "sell");
        sideFld.setValue("buy");

        ComboBox<String> typeFld = new ComboBox<>();
        typeFld.getItems().addAll("market", "limit", "stop", "stop_limit");
        typeFld.setValue("market");

        TextField qtyFld = new TextField();
        qtyFld.setPromptText("10");

        ComboBox<String> tifFld = new ComboBox<>();
        tifFld.getItems().addAll("day", "gtc", "ioc", "fok");
        tifFld.setValue("day");

        TextField limitFld = new TextField();
        limitFld.setPromptText("limit price");
        TextField stopFld = new TextField();
        stopFld.setPromptText("stop price");

        Runnable refreshEnabled = () -> {
            String t = typeFld.getValue();
            boolean limit = "limit".equals(t) || "stop_limit".equals(t);
            boolean stop = "stop".equals(t) || "stop_limit".equals(t);
            limitFld.setDisable(!limit);
            stopFld.setDisable(!stop);
        };
        refreshEnabled.run();
        typeFld.setOnAction(e -> refreshEnabled.run());

        Button submit = new Button("Place Order");
        submit.getStyleClass().add(Styles.ACCENT);
        submit.setDefaultButton(true);

        Label status = new Label(" ");
        status.setWrapText(true);
        status.setMinHeight(40);

        submit.setOnAction(e -> {
            String sym = symbolFld.getText() == null ? "" : symbolFld.getText().trim();
            String qty = qtyFld.getText() == null ? "" : qtyFld.getText().trim();
            String t = typeFld.getValue();
            if (sym.isEmpty()) {
                setError(status, "Symbol required");
                return;
            }
            if (qty.isEmpty()) {
                setError(status, "Quantity required");
                return;
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("symbol", sym);
            body.put("qty", qty);
            body.put("side", sideFld.getValue());
            body.put("type", t);
            body.put("time_in_force", tifFld.getValue());
            if ("limit".equals(t) || "stop_limit".equals(t)) {
                String lp = limitFld.getText() == null ? "" : limitFld.getText().trim();
                if (lp.isEmpty()) {
                    setError(status, "Limit price required for " + t);
                    return;
                }
                body.put("limit_price", lp);
            }
            if ("stop".equals(t) || "stop_limit".equals(t)) {
                String sp = stopFld.getText() == null ? "" : stopFld.getText().trim();
                if (sp.isEmpty()) {
                    setError(status, "Stop price required for " + t);
                    return;
                }
                body.put("stop_price", sp);
            }

            submit.setDisable(true);
            status.setStyle("");
            status.setText("Submitting " + sideFld.getValue() + " " + qty + " " + sym + "…");
            Thread worker = new Thread(() -> {
                try {
                    Order order = client.submitOrder(body);
                    Platform.runLater(() -> {
                        status.setStyle("-fx-text-fill: -color-success-fg;");
                        status.setText("OK  id=" + order.id() + "  status=" + order.status());
                        submit.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        setError(status, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                        submit.setDisable(false);
                    });
                }
            }, "place-order");
            worker.setDaemon(true);
            worker.start();
        });

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        int r = 0;
        form.add(formLabel("Symbol"), 0, r);
        form.add(symbolFld, 1, r++);
        form.add(formLabel("Side"), 0, r);
        form.add(sideFld, 1, r++);
        form.add(formLabel("Order type"), 0, r);
        form.add(typeFld, 1, r++);
        form.add(formLabel("Quantity"), 0, r);
        form.add(qtyFld, 1, r++);
        form.add(formLabel("Time in force"), 0, r);
        form.add(tifFld, 1, r++);
        form.add(formLabel("Limit price"), 0, r);
        form.add(limitFld, 1, r++);
        form.add(formLabel("Stop price"), 0, r);
        form.add(stopFld, 1, r++);
        r++;
        form.add(submit, 1, r++);
        form.add(status, 1, r);
        for (Node n : form.getChildren()) {
            if (n instanceof TextField || n instanceof ComboBox) {
                ((Region) n).setPrefWidth(260);
            }
        }

        VBox card = new VBox(form);
        card.setPadding(new Insets(20, 24, 24, 24));
        card.setStyle(
                "-fx-background-color: -color-bg-subtle;"
                        + "-fx-background-radius: 10;"
                        + "-fx-border-color: -color-border-default;"
                        + "-fx-border-radius: 10;"
                        + "-fx-border-width: 1;");
        card.setMaxWidth(480);

        VBox tab = new VBox(card);
        tab.setPadding(new Insets(20, 4, 4, 4));
        return tab;
    }

    private void openAddAllocationDialog() {
        Dialog<Allocation> dialog = new Dialog<>();
        dialog.setTitle("Add Allocation");
        dialog.initOwner(stage);
        dialog.getDialogPane().getStylesheets().add(new PrimerDark().getUserAgentStylesheet());

        ComboBox<String> symbolCombo = new ComboBox<>();
        symbolCombo.setPrefWidth(260);
        Map<String, BigDecimal> qtyBySymbol = new HashMap<>();
        for (Position p : latestPositions) {
            symbolCombo.getItems().add(p.symbol());
            qtyBySymbol.put(p.symbol(), p.qty());
        }
        if (!symbolCombo.getItems().isEmpty()) {
            symbolCombo.setValue(symbolCombo.getItems().get(0));
        }

        Label availableLabel = new Label("");
        availableLabel.getStyleClass().add(Styles.TEXT_MUTED);

        TextField qtyField = new TextField();
        qtyField.setPrefWidth(260);

        ComboBox<String> strategyCombo = new ComboBox<>();
        strategyCombo.getItems().addAll("Trailing Stop", "Do Nothing");
        strategyCombo.setValue("Trailing Stop");
        strategyCombo.setPrefWidth(260);

        Runnable refreshAvailable = () -> {
            String sym = symbolCombo.getValue();
            if (sym == null) {
                availableLabel.setText("");
                return;
            }
            BigDecimal pos = qtyBySymbol.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal allocated = BigDecimal.ZERO;
            for (Allocation a : engine.snapshot()) {
                if (sym.equals(a.symbol()) && a.qty() != null)
                    allocated = allocated.add(a.qty());
            }
            BigDecimal free = pos.subtract(allocated);
            availableLabel.setText("Position: " + plain(pos) + "   allocated: " + plain(allocated)
                    + "   free: " + plain(free));
        };
        symbolCombo.valueProperty().addListener((o, a, b) -> refreshAvailable.run());
        refreshAvailable.run();

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 10, 10, 10));
        int r = 0;
        grid.add(formLabel("Symbol"), 0, r);
        grid.add(symbolCombo, 1, r++);
        grid.add(formLabel("Quantity"), 0, r);
        grid.add(qtyField, 1, r++);
        grid.add(formLabel("Strategy"), 0, r);
        grid.add(strategyCombo, 1, r++);
        grid.add(new Label(), 0, r);
        grid.add(availableLabel, 1, r);

        dialog.getDialogPane().setContent(grid);

        ButtonType addType = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addType, ButtonType.CANCEL);
        Button addBtn = (Button) dialog.getDialogPane().lookupButton(addType);
        addBtn.getStyleClass().add(Styles.ACCENT);

        addBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            String sym = symbolCombo.getValue();
            String qtyStr = qtyField.getText() == null ? "" : qtyField.getText().trim();
            BigDecimal qty;
            try {
                qty = new BigDecimal(qtyStr);
            } catch (Exception ex) {
                showError("Quantity must be a number");
                e.consume();
                return;
            }
            if (sym == null || sym.isEmpty()) {
                showError("Pick a symbol");
                e.consume();
                return;
            }
            if (qty.signum() <= 0) {
                showError("Quantity must be > 0");
                e.consume();
                return;
            }
            BigDecimal pos = qtyBySymbol.getOrDefault(sym, BigDecimal.ZERO);
            BigDecimal allocated = BigDecimal.ZERO;
            for (Allocation a : engine.snapshot()) {
                if (sym.equals(a.symbol()) && a.qty() != null)
                    allocated = allocated.add(a.qty());
            }
            BigDecimal free = pos.subtract(allocated);
            if (qty.compareTo(free) > 0) {
                showError("Only " + plain(free) + " " + sym + " unallocated; can't allocate " + plain(qty));
                e.consume();
            }
        });

        dialog.setResultConverter(btn -> {
            if (btn == addType) {
                String sym = symbolCombo.getValue();
                BigDecimal qty = new BigDecimal(qtyField.getText().trim());
                String type = "Trailing Stop".equals(strategyCombo.getValue())
                        ? Allocation.TRAILING_STOP
                        : Allocation.DO_NOTHING;
                return Allocation.create(sym, qty, type);
            }
            return null;
        });

        dialog.showAndWait().ifPresent(engine::addAllocation);
    }

    private static void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private static void setError(Label l, String msg) {
        l.setStyle("-fx-text-fill: -color-danger-fg;");
        l.setText("Error: " + msg);
    }

    private static Label formLabel(String s) {
        Label l = new Label(s);
        l.setMinWidth(110);
        return l;
    }

    private TableView<PositionRow> buildPositionsTable() {
        TableView<PositionRow> tv = new TableView<>(positionRows);
        tv.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
        tv.setPlaceholder(new Label("No open positions"));
        tv.setFixedCellSize(32);
        tv.getColumns().add(textCol("Symbol", PositionRow::symbol));
        tv.getColumns().add(numCol("Qty", PositionRow::qty));
        tv.getColumns().add(numCol("Entry", PositionRow::entry));
        tv.getColumns().add(numCol("Last", PositionRow::last));
        tv.getColumns().add(plCol("P&L", PositionRow::pl));
        tv.getColumns().add(plCol("P&L %", PositionRow::plPct));
        tv.getColumns().add(textCol("Allocations", PositionRow::allocations));
        return tv;
    }

    private TableView<StrategyRow> buildStrategiesTable() {
        TableView<StrategyRow> tv = new TableView<>(filteredStrategyRows);
        tv.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
        tv.setPlaceholder(new Label("No allocations — click '+ Add Allocation' to create one"));
        tv.setFixedCellSize(32);
        tv.getColumns().add(textCol("Symbol", StrategyRow::symbol));
        tv.getColumns().add(numCol("Qty", StrategyRow::qty));
        tv.getColumns().add(textCol("Strategy", StrategyRow::strategy));
        tv.getColumns().add(numCol("Trail High", StrategyRow::high));
        tv.getColumns().add(numCol("Stop Trigger", StrategyRow::stop));
        tv.getColumns().add(textCol("Stop Order", StrategyRow::orderId));
        tv.getColumns().add(removeCol());
        return tv;
    }

    private TableColumn<StrategyRow, Void> removeCol() {
        TableColumn<StrategyRow, Void> c = new TableColumn<>("");
        c.setMinWidth(110);
        c.setMaxWidth(110);
        c.setCellFactory(col -> new TableCell<>() {
            private final Button btn = new Button("Remove");
            {
                btn.getStyleClass().add(Styles.DANGER);
                btn.setOnAction(e -> {
                    StrategyRow r = getTableView().getItems().get(getIndex());
                    confirmAndRemove(r);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : btn);
            }
        });
        return c;
    }

    private void confirmAndRemove(StrategyRow row) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove " + row.strategy() + " allocation for " + row.qty() + " " + row.symbol()
                        + "?\nAny parked stop order will be canceled.",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                engine.removeAllocation(row.id());
            }
        });
    }

    private TableView<OrderRow> buildOrdersTable() {
        TableView<OrderRow> tv = new TableView<>(orderRows);
        tv.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
        tv.setPlaceholder(new Label("No pending orders"));
        tv.setFixedCellSize(32);
        tv.getColumns().add(textCol("Symbol", OrderRow::symbol));
        tv.getColumns().add(textCol("Side", OrderRow::side));
        tv.getColumns().add(textCol("Type", OrderRow::type));
        tv.getColumns().add(numCol("Qty", OrderRow::qty));
        tv.getColumns().add(numCol("Stop", OrderRow::stop));
        tv.getColumns().add(numCol("Limit", OrderRow::limit));
        tv.getColumns().add(textCol("Status", OrderRow::status));
        tv.getColumns().add(textCol("Submitted (ET)", OrderRow::submitted));
        tv.getColumns().add(cancelOrderCol());
        return tv;
    }

    private TableColumn<OrderRow, Void> cancelOrderCol() {
        TableColumn<OrderRow, Void> c = new TableColumn<>("");
        c.setMinWidth(90);
        c.setMaxWidth(90);
        c.setCellFactory(col -> new TableCell<>() {
            private final Button btn = new Button("Cancel");
            {
                btn.getStyleClass().add(Styles.DANGER);
                btn.setStyle("-fx-font-size: 11px;");
                btn.setOnAction(e -> {
                    OrderRow r = getTableView().getItems().get(getIndex());
                    confirmAndCancelOrder(r);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : btn);
            }
        });
        return c;
    }

    private void confirmAndCancelOrder(OrderRow row) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Cancel " + row.side() + " " + row.type() + " order for "
                        + row.qty() + " " + row.symbol() + " (status: " + row.status() + ")?",
                ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        a.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.OK) {
                Thread worker = new Thread(() -> {
                    try {
                        boolean cancelled = client.cancelOrder(row.orderId());
                        Platform.runLater(() -> {
                            if (cancelled) {
                                Alert ok = new Alert(Alert.AlertType.INFORMATION, "Order cancelled successfully.",
                                        ButtonType.OK);
                                ok.setHeaderText(null);
                                ok.showAndWait();
                            } else {
                                Alert warn = new Alert(Alert.AlertType.WARNING,
                                        "Order is already terminal (filled/cancelled).", ButtonType.OK);
                                warn.setHeaderText(null);
                                warn.showAndWait();
                            }
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> {
                            Alert err = new Alert(Alert.AlertType.ERROR,
                                    "Failed to cancel: " + (ex.getMessage() == null ? ex.getClass().getSimpleName()
                                            : ex.getMessage()),
                                    ButtonType.OK);
                            err.setHeaderText(null);
                            err.showAndWait();
                        });
                    }
                }, "cancel-order");
                worker.setDaemon(true);
                worker.start();
            }
        });
    }

    private static <T> TableColumn<T, String> textCol(String name, Function<T, String> getter) {
        TableColumn<T, String> c = new TableColumn<>(name);
        c.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        return c;
    }

    private static <T> TableColumn<T, String> numCol(String name, Function<T, String> getter) {
        TableColumn<T, String> c = textCol(name, getter);
        c.setStyle("-fx-alignment: CENTER_RIGHT;" + MONO);
        return c;
    }

    private static <T> TableColumn<T, String> plCol(String name, Function<T, String> getter) {
        TableColumn<T, String> c = numCol(name, getter);
        c.setCellFactory(col -> new TableCell<T, String>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                String base = "-fx-alignment: CENTER_RIGHT;" + MONO;
                if (empty || value == null || value.equals("—")) {
                    setText(value);
                    setStyle(base);
                } else {
                    setText(value);
                    String color = value.startsWith("-") ? "-color-danger-fg" : "-color-success-fg";
                    setStyle(base + "-fx-text-fill: " + color + ";");
                }
            }
        });
        return c;
    }

    private void startPolling() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Account account = client.getAccount();
                    List<Position> positions = client.listPositions();
                    List<Order> orders = client.listOpenOrders();
                    Clock clock = client.getClock();
                    List<Allocation> allocs = engine.snapshot();
                    Platform.runLater(() -> applyUpdate(account, positions, orders, clock, allocs));
                    Thread.sleep(REFRESH.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    Platform.runLater(() -> updatedLabel.setText("Error: " + msg));
                    try {
                        Thread.sleep(REFRESH.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "ui-poller");
        t.setDaemon(true);
        t.start();
    }

    private void applyUpdate(Account account, List<Position> positions, List<Order> orders, Clock clock,
            List<Allocation> allocations) {
        latestPositions = positions;

        boolean open = clock.isOpen();
        marketDot.setFill(open ? Color.web("#3fb950") : Color.web("#8b949e"));
        marketLabel.setText(open ? "Market OPEN" : "Market CLOSED");
        clockLabel.setText(TIME_FMT.format(Instant.now()) + " ET");
        updatedLabel.setText("Updated " + TIME_FMT.format(Instant.now()));

        equityVal.setText("$" + money(account.equity()));
        cashVal.setText("$" + money(account.cash()));
        buyingPowerVal.setText("$" + money(account.buyingPower()));

        BigDecimal totalPl = BigDecimal.ZERO;
        for (Position p : positions) {
            if (p.unrealizedPl() != null)
                totalPl = totalPl.add(p.unrealizedPl());
        }
        boolean nonNeg = totalPl.signum() >= 0;
        plVal.setText((nonNeg ? "+$" : "-$") + money(totalPl.abs()));
        plVal.setStyle("-fx-font-size: 22px; -fx-font-weight: 700;" + MONO
                + "-fx-text-fill: " + (nonNeg ? "-color-success-fg" : "-color-danger-fg") + ";");

        // Build allocation map per symbol for positions and strategies tables.
        Map<String, List<Allocation>> bySymbol = new HashMap<>();
        for (Allocation a : allocations) {
            bySymbol.computeIfAbsent(a.symbol(), k -> new ArrayList<>()).add(a);
        }

        List<PositionRow> pr = new ArrayList<>();
        for (Position p : positions) {
            pr.add(new PositionRow(
                    p.symbol(),
                    plain(p.qty()),
                    money(p.avgEntryPrice()),
                    money(p.currentPrice()),
                    signedMoney(p.unrealizedPl()),
                    pctOf(p),
                    allocationsLabel(p, bySymbol.getOrDefault(p.symbol(), List.of()))));
        }
        positionRows.setAll(pr);

        List<StrategyRow> sr = new ArrayList<>();
        for (Allocation a : allocations) {
            sr.add(new StrategyRow(
                    a.id(),
                    a.symbol(),
                    plain(a.qty()),
                    Allocation.label(a.strategyType()),
                    money(a.trailHigh()),
                    money(StrategyEngine.floorOf(a.trailHigh())),
                    a.stopOrderId() == null ? "—" : truncate(a.stopOrderId(), 18)));
        }
        sr.sort((x, y) -> {
            int c = x.symbol().compareTo(y.symbol());
            return c != 0 ? c : x.strategy().compareTo(y.strategy());
        });
        strategyRows.setAll(sr);

        List<OrderRow> or = new ArrayList<>();
        for (Order o : orders) {
            or.add(new OrderRow(
                    o.symbol(),
                    o.side() == null ? "—" : o.side(),
                    o.orderType() == null ? "—" : o.orderType(),
                    plain(o.qty()),
                    money(o.stopPrice()),
                    money(o.limitPrice()),
                    o.status() == null ? "—" : o.status(),
                    o.submittedAt() == null ? "—" : TIME_FMT.format(o.submittedAt()),
                    o.id()));
        }
        orderRows.setAll(or);
    }

    private static String allocationsLabel(Position p, List<Allocation> allocs) {
        if (allocs.isEmpty())
            return "all unallocated";
        BigDecimal totalAlloc = BigDecimal.ZERO;
        Map<String, BigDecimal> byType = new LinkedHashMap<>();
        for (Allocation a : allocs) {
            if (a.qty() == null)
                continue;
            totalAlloc = totalAlloc.add(a.qty());
            byType.merge(a.strategyType(), a.qty(), BigDecimal::add);
        }
        StringBuilder sb = new StringBuilder();
        for (var e : byType.entrySet()) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(plain(e.getValue())).append(" ").append(shortLabel(e.getKey()));
        }
        BigDecimal free = p.qty() == null ? BigDecimal.ZERO : p.qty().subtract(totalAlloc);
        if (free.signum() > 0) {
            sb.append(", ").append(plain(free)).append(" free");
        }
        return sb.toString();
    }

    private static String shortLabel(String type) {
        return switch (type) {
            case Allocation.TRAILING_STOP -> "TS";
            case Allocation.DO_NOTHING -> "DN";
            default -> type;
        };
    }

    private static String money(BigDecimal v) {
        if (v == null)
            return "—";
        return String.format(Locale.US, "%,.2f", v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String signedMoney(BigDecimal v) {
        if (v == null)
            return "—";
        BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
        return (r.signum() >= 0 ? "+" : "-") + String.format(Locale.US, "%,.2f", r.abs());
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static String pctOf(Position p) {
        if (p.unrealizedPl() == null || p.avgEntryPrice() == null || p.qty() == null
                || p.avgEntryPrice().signum() == 0 || p.qty().signum() == 0) {
            return "—";
        }
        BigDecimal cost = p.avgEntryPrice().multiply(p.qty());
        if (cost.signum() == 0)
            return "—";
        BigDecimal pct = p.unrealizedPl().divide(cost, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        return (pct.signum() >= 0 ? "+" : "") + pct + "%";
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public record PositionRow(String symbol, String qty, String entry, String last,
            String pl, String plPct, String allocations) {
    }

    public record StrategyRow(String id, String symbol, String qty, String strategy,
            String high, String stop, String orderId) {
    }

    public record OrderRow(String symbol, String side, String type, String qty,
            String stop, String limit, String status, String submitted, String orderId) {
    }
}
