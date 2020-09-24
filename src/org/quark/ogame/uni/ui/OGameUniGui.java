package org.quark.ogame.uni.ui;

import java.awt.Color;
import java.awt.Component;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;

import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.LineBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.observe.Observable;
import org.observe.ObservableValue;
import org.observe.SettableValue;
import org.observe.SimpleObservable;
import org.observe.collect.CollectionChangeType;
import org.observe.collect.ObservableCollection;
import org.observe.config.ObservableConfig;
import org.observe.config.ObservableConfigFormat;
import org.observe.config.SyncValueSet;
import org.observe.util.TypeTokens;
import org.observe.util.swing.CategoryRenderStrategy;
import org.observe.util.swing.JustifiedBoxLayout;
import org.observe.util.swing.ModelCell;
import org.observe.util.swing.ObservableCellRenderer;
import org.observe.util.swing.ObservableSwingUtils;
import org.observe.util.swing.PanelPopulation;
import org.qommons.QommonsUtils;
import org.qommons.StringUtils;
import org.qommons.ValueHolder;
import org.qommons.collect.BetterList;
import org.qommons.collect.CollectionElement;
import org.qommons.collect.ElementId;
import org.qommons.io.Format;
import org.qommons.io.SpinnerFormat;
import org.quark.ogame.OGameUtils;
import org.quark.ogame.roi.OGameRoiSettings;
import org.quark.ogame.uni.Account;
import org.quark.ogame.uni.AccountClass;
import org.quark.ogame.uni.AccountUpgradeType;
import org.quark.ogame.uni.BuildingType;
import org.quark.ogame.uni.OGameEconomyRuleSet;
import org.quark.ogame.uni.OGameEconomyRuleSet.FullProduction;
import org.quark.ogame.uni.OGameEconomyRuleSet.Production;
import org.quark.ogame.uni.OGamePageReader;
import org.quark.ogame.uni.OGameRuleSet;
import org.quark.ogame.uni.Planet;
import org.quark.ogame.uni.PlannedUpgrade;
import org.quark.ogame.uni.PointType;
import org.quark.ogame.uni.ResourceType;
import org.quark.ogame.uni.ShipyardItemType;
import org.quark.ogame.uni.TradeRatios;
import org.quark.ogame.uni.UpgradeAccount;
import org.quark.ogame.uni.UpgradeAccount.UpgradePlanet;
import org.quark.ogame.uni.UpgradeCost;
import org.quark.ogame.uni.versions.OGameRuleSet710;
import org.quark.ogame.uni.versions.OGameRuleSet711;
import org.quark.ogame.uni.versions.OGameRuleSet750;

import com.google.common.reflect.TypeToken;

public class OGameUniGui extends JPanel {
	private static final Production ZERO = new Production(Collections.emptyMap(), 0, 0);

	private final ObservableConfig theConfig;
	private final List<OGameRuleSet> theRuleSets;
	private final SyncValueSet<Account> theAccounts;
	private final SettableValue<OGameRuleSet> theSelectedRuleSet;
	private final SettableValue<Account> theSelectedAccount;

	private final SimpleObservable<Void> thePlanetRefresh;
	private final ObservableCollection<PlanetWithProduction> thePlanets;
	private final SettableValue<PlanetWithProduction> theSelectedPlanet;
	private final ObservableValue<UpgradeAccount> theUpgradeAccount;

	private final ObservableCollection<PlanetWithProduction> theTotalProduction;
	private final ObservableCollection<PlanetWithProduction> thePlanetsWithTotal;
	private final ObservableCollection<PlannedAccountUpgrade> theUpgrades;

	private final SettableValue<File> thePlanetEmpireFile;
	private final SettableValue<File> theMoonEmpireFile;

	private final PlanetTable thePlanetPanel;
	private final ProductionPanel theProductionPanel;
	private final ResourceSettingsPanel theResourceSettingsPanel;
	private final UpgradePanel theUpgradePanel;
	private final HoldingsPanel theHoldingsPanel;
	private final FlightPanel theFlightPanel;

	public OGameUniGui(ObservableConfig config, List<OGameRuleSet> ruleSets, SyncValueSet<Account> accounts) {
		theConfig = config;
		theRuleSets = ruleSets;
		theAccounts = accounts;
		theSelectedRuleSet = config.observeValue("selected-rule-set").map(TypeTokens.get().of(OGameRuleSet.class), name -> {
			for (OGameRuleSet ruleSet : theRuleSets) {
				if (ruleSet.getName().equals(name)) {
					return ruleSet;
				}
			}
			return theRuleSets.get(theRuleSets.size() - 1);
		}, OGameRuleSet::getName, null);
		theSelectedAccount = config.asValue(Account.class).at("selected-account")
			.withFormat(
				ObservableConfigFormat.<Account> buildReferenceFormat(fv -> accounts.getValues(), () -> accounts.getValues().peekFirst())//
					.withField("id", Account::getId, ObservableConfigFormat.INT)//
					.build())
			.buildValue(null);
		theUpgradeAccount = theSelectedAccount.map(TypeTokens.get().of(UpgradeAccount.class), a -> {
			return a == null ? null : new UpgradeAccount(a);
		}, opts -> opts.cache(true).reEvalOnUpdate(false).fireIfUnchanged(true));

		thePlanetRefresh = new SimpleObservable<>();
		thePlanets = ObservableCollection
			.flattenValue(theUpgradeAccount.map(
				account -> account == null ? ObservableCollection.of(TypeTokens.get().of(Planet.class)) : account.getPlanets().getValues()))
			.flow().refresh(thePlanetRefresh)//
			.transform(TypeTokens.get().of(PlanetWithProduction.class), tx -> tx.cache(true).reEvalOnUpdate(false).map(this::productionFor))//
			.collect();
		theSelectedPlanet = SettableValue.build(PlanetWithProduction.class).safe(false).build();

		PlanetWithProduction total = new PlanetWithProduction(null, null)//
			.setProduction(ZERO, ZERO, ZERO, ZERO).setUpgradeProduction(ZERO, ZERO, ZERO, ZERO);
		theTotalProduction = ObservableCollection.build(PlanetWithProduction.class).safe(false).build().with(total);
		// Update total for each change
		thePlanets.simpleChanges().act(__ -> updateTotalProduction(total, ZERO));
		thePlanetsWithTotal = ObservableCollection.flattenCollections(TypeTokens.get().of(PlanetWithProduction.class), //
			getPlanets(), theTotalProduction).collect();

		TypeToken<ObservableCollection<PlannedUpgrade>> upgradeCollType = TypeTokens.get().keyFor(ObservableCollection.class)
			.parameterized(PlannedUpgrade.class);
		theUpgrades = ObservableCollection
			.flattenValue(
				theSelectedAccount.map(upgradeCollType, account -> account.getPlannedUpgrades().getValues(), opts -> opts.nullToNull(true)))//
			.flow().map(TypeTokens.get().of(PlannedAccountUpgrade.class), pu -> new PlannedAccountUpgrade(pu)).collect();
		theUpgradeAccount.changes().act(evt -> {
			UpgradeAccount ua = evt.getNewValue();
			if (ua == null) {
				return;
			} else if (evt.getNewValue() == evt.getOldValue()) {
				for (PlannedAccountUpgrade upgrade : theUpgrades) {
					if (upgrade.getPlanet() == null) {
						upgrade.clear();
					}
				}
				return;
			} else {
				for (CollectionElement<PlannedAccountUpgrade> upgrade : theUpgrades.elements()) {
					BetterList<ElementId> upgradeEl = theUpgrades.getSourceElements(upgrade.getElementId(),
						ua.getWrapped().getPlannedUpgrades().getValues());
					if (upgradeEl.isEmpty()) {
						return;
					}
					ua.withUpgrade(upgrade.get().getUpgrade());
				}
			}
		});
		theUpgrades.subscribe(evt -> {
			UpgradeAccount ua = theUpgradeAccount.get();
			if (ua == null) {
				return;
			}
			if (evt.getType() != CollectionChangeType.add) {
				ua.withoutUpgrade(evt.getOldValue().getUpgrade());
			}
			if (evt.getType() != CollectionChangeType.remove) {
				BetterList<ElementId> upgradeEl = theUpgrades.getSourceElements(evt.getElementId(),
					ua.getWrapped().getPlannedUpgrades().getValues());
				if (upgradeEl.isEmpty()) {
					return;
				}
				ua.withUpgrade(evt.getNewValue().getUpgrade());
			}
		}, true);
		thePlanets.changes().act(evt -> {
			if (evt.type == CollectionChangeType.set) {
				for (PlanetWithProduction p : evt.getValues()) {
					for (PlannedAccountUpgrade upgrade : theUpgrades) {
						if (upgrade.getPlanet() == p.planet) {
							upgrade.clear();
						}
					}
					updateProduction(p);
				}
			}
		});
		theUpgrades.simpleChanges().act(evt -> ObservableSwingUtils.onEQ(this::refreshProduction));
		refreshProduction();
		updateTotalProduction(total, ZERO);

		thePlanetEmpireFile = SettableValue.build(File.class).safe(false).build();
		theMoonEmpireFile = SettableValue.build(File.class).safe(false).build();

		thePlanetPanel = new PlanetTable(this);
		theProductionPanel = new ProductionPanel(this);
		theResourceSettingsPanel = new ResourceSettingsPanel(this);
		theUpgradePanel = new UpgradePanel(this);
		theHoldingsPanel = new HoldingsPanel(this);
		theFlightPanel = new FlightPanel(this);

		initComponents();
	}

	public ObservableConfig getConfig() {
		return theConfig;
	}

	public SettableValue<OGameRuleSet> getRules() {
		return theSelectedRuleSet;
	}

	public SettableValue<Account> getSelectedAccount() {
		return theSelectedAccount;
	}

	public ObservableValue<UpgradeAccount> getUpgradeAccount() {
		return theUpgradeAccount;
	}

	public ObservableCollection<PlanetWithProduction> getPlanets() {
		return thePlanets;
	}

	/** @return {@link PlanetWithProduction} values for each planet, in addition to a terminal rows for total production */
	public ObservableCollection<PlanetWithProduction> getPlanetsWithTotal() {
		return thePlanetsWithTotal;
	}

	public SettableValue<PlanetWithProduction> getSelectedPlanet() {
		return theSelectedPlanet;
	}

	/** @return An ObservableCollection with a single element that is the total production of the selected account */
	public ObservableCollection<PlanetWithProduction> getTotalProduction() {
		return theTotalProduction;
	}

	public ObservableCollection<PlannedAccountUpgrade> getUpgrades() {
		return theUpgrades;
	}

	public void refreshProduction() {
		thePlanetRefresh.onNext(null);
	}

	public PlanetWithProduction createPlanet() {
		CollectionElement<Planet> newPlanet = theSelectedAccount.get().getPlanets().create()//
			.with(Planet::getName,
				StringUtils.getNewItemName(theSelectedAccount.get().getPlanets().getValues(), Planet::getName, "New Planet",
					StringUtils.SIMPLE_DUPLICATES))
			.with(Planet::getBaseFields, 173)//
			.with(Planet::getMetalUtilization, 100)//
			.with(Planet::getCrystalUtilization, 100)//
			.with(Planet::getDeuteriumUtilization, 100)//
			.with(Planet::getSolarPlantUtilization, 100)//
			.with(Planet::getSolarSatelliteUtilization, 100)//
			.with(Planet::getFusionReactorUtilization, 100)//
			.with(Planet::getCrawlerUtilization, 100)//
			.create();
		return thePlanets.getElementsBySource(newPlanet.getElementId(), theSelectedAccount.get().getPlanets().getValues()).getFirst().get();
	}

	public PlanetTable getPlanetPanel() {
		return thePlanetPanel;
	}

	public UpgradePanel getUpgradePanel() {
		return theUpgradePanel;
	}

	public HoldingsPanel getHoldings() {
		return theHoldingsPanel;
	}

	PlanetWithProduction productionFor(Planet planet) {
		return new PlanetWithProduction(((UpgradePlanet) planet).getWrapped(), (UpgradePlanet) planet)//
			.setProduction(ZERO, ZERO, ZERO, ZERO).setUpgradeProduction(ZERO, ZERO, ZERO, ZERO);
	}

	void updateProduction(PlanetWithProduction p) {
		OGameEconomyRuleSet eco = theSelectedRuleSet.get().economy();
		UpgradeAccount account = theUpgradeAccount.get();
		p.upgradePlanet.optimizeEnergy(eco);
		Production energy = eco.getProduction(account, p.upgradePlanet, ResourceType.Energy, 1);
		double energyFactor = Math.min(1, energy.totalProduction * 1.0 / energy.totalConsumption);
		Production metal = eco.getProduction(account, p.upgradePlanet, ResourceType.Metal, energyFactor);
		Production crystal = eco.getProduction(account, p.upgradePlanet, ResourceType.Crystal, energyFactor);
		Production deuterium = eco.getProduction(account, p.upgradePlanet, ResourceType.Deuterium, energyFactor);
		p.setUpgradeProduction(energy, metal, crystal, deuterium);

		energy = eco.getProduction(account.getWrapped(), p.planet, ResourceType.Energy, 1);
		energyFactor = Math.min(1, energy.totalProduction * 1.0 / energy.totalConsumption);
		metal = eco.getProduction(account.getWrapped(), p.planet, ResourceType.Metal, energyFactor);
		crystal = eco.getProduction(account.getWrapped(), p.planet, ResourceType.Crystal, energyFactor);
		deuterium = eco.getProduction(account.getWrapped(), p.planet, ResourceType.Deuterium, energyFactor);
		p.setProduction(energy, metal, crystal, deuterium);
	}

	void updateTotalProduction(PlanetWithProduction total, Production zero) {
		// At some point there may be a use case for adding each component of production,
		// e.g. to see how much deuterium you're using on fusion throughout the empire.
		// But at the moment, all that's needed is the total net production of material resources
		// and the calculation for all that detail is cumbersome.
		int metal = 0, crystal = 0, deuterium = 0;
		int upgradeMetal = 0, upgradeCrystal = 0, upgradeDeuterium = 0;
		for (PlanetWithProduction planet : thePlanets) {
			metal += planet.getMetal().totalNet;
			crystal += planet.getCrystal().totalNet;
			deuterium += planet.getDeuterium().totalNet;

			upgradeMetal += planet.getUpgradeMetal().totalNet;
			upgradeCrystal += planet.getUpgradeCrystal().totalNet;
			upgradeDeuterium += planet.getUpgradeDeuterium().totalNet;
		}
		total.setProduction(zero, //
			new Production(Collections.emptyMap(), metal, 0), //
			new Production(Collections.emptyMap(), crystal, 0), //
				new Production(Collections.emptyMap(), deuterium, 0))//
			.setUpgradeProduction(zero, //
				new Production(Collections.emptyMap(), upgradeMetal, 0), //
				new Production(Collections.emptyMap(), upgradeCrystal, 0), //
				new Production(Collections.emptyMap(), upgradeDeuterium, 0));
		theTotalProduction.mutableElement(theTotalProduction.getTerminalElement(true).getElementId()).set(total);
	}

	void initComponents() {
		/* TODO
		 * ROI sequence
		 * Production value in account table
		 * Spitballing (general upgrade costs without reference to an account)
		 * Hyperspace tech guide
		 */
		ObservableCollection<Account> referenceAccounts = ObservableCollection.flattenCollections(TypeTokens.get().of(Account.class), //
			ObservableCollection.of(TypeTokens.get().of(Account.class), (Account) null), //
			theAccounts.getValues().flow().refresh(theSelectedAccount.noInitChanges())
				.filter(account -> account == theSelectedAccount.get() ? "Selected" : null).collect()//
		).collect();
		SettableValue<String> selectedTab = theConfig.asValue(String.class).at("selected-tab").withFormat(Format.TEXT, () -> "settings")
			.buildValue(null);

		PanelPopulation.populateVPanel(this, Observable.empty())//
			.addSplit(true,
				mainSplit -> mainSplit.withSplitLocation(150).fill().fillV()//
					.firstV(accountSelectPanel -> accountSelectPanel//
						.fill().fillV().addTable(theAccounts.getValues(),
							accountTable -> accountTable//
								.fill().fillV().withItemName("account").withAdaptiveHeight(1, 2, 10)//
								.dragSourceRow(d -> d.toObject()).dragAcceptRow(d -> d.fromObject())//
								.withNameColumn(Account::getName, Account::setName, true,
									nameColumn -> nameColumn.withWidths(50, 100, 300)//
										.withMutation(nameMutator -> nameMutator.asText(SpinnerFormat.NUMERICAL_TEXT)))//
								.withColumn("Universe", String.class, account -> account.getUniverse().getName(),
									uniColumn -> uniColumn//
										.withWidths(50, 100, 300)//
										.withMutation(uniMutator -> uniMutator.mutateAttribute2((account, uniName) -> {
											account.getUniverse().setName(uniName);
											return uniName;
										}).asText(Format.TEXT)))//
								.withColumn("Planets", Integer.class, account -> account.getPlanets().getValues().size(), //
									planetColumn -> planetColumn.withWidths(50, 50, 50))//
								.withColumn("Eco Points", String.class, account -> OGameUniGui.this.printPoints(account, PointType.Economy), //
									pointsColumn -> pointsColumn.withWidths(75, 75, 75))//
								.withColumn("Rsrch Points", String.class,
									account -> OGameUniGui.this.printPoints(account, PointType.Research), //
									pointsColumn -> pointsColumn.withWidths(75, 75, 75))//
								.withSelection(theSelectedAccount, false)//
								.withAdd(() -> initAccount(theAccounts.create()//
									.with("name",
										StringUtils.getNewItemName(theAccounts.getValues(), Account::getName, "New Account",
											StringUtils.PAREN_DUPLICATES))//
									.create().get()), null)//
								.withRemove(accounts -> theAccounts.getValues().removeAll(accounts),
									action -> action//
										.confirmForItems("Delete Accounts?", "Are you sure you want to delete ", null, true))//
								.withCopy(account -> {
									Account copy = theAccounts.create().copy(account)//
										.with(Account::getId, getNewId())//
										.with(Account::getName,
											StringUtils.getNewItemName(theAccounts.getValues(), Account::getName, account.getName(),
												StringUtils.PAREN_DUPLICATES))//
										.create().get();
									return copy;
								}, null)//
					)//
					).lastV(selectedAccountPanel -> selectedAccountPanel.visibleWhen(theSelectedAccount.map(account -> account != null))//
						.addTabs(
							tabs -> tabs.fill().fillV().withSelectedTab(selectedTab)//
								.withVTab("settings",
									acctSettingsPanel -> acctSettingsPanel//
										.fill()//
										.addTextField("Name:",
											theSelectedAccount.asFieldEditor(TypeTokens.get().STRING, Account::getName, Account::setName,
												null),
											SpinnerFormat.NUMERICAL_TEXT, f -> f.fill())//
										.addTextField("Universe Name:",
											theSelectedAccount.asFieldEditor(TypeTokens.get().STRING,
												account -> account.getUniverse().getName(),
												(account, name) -> account.getUniverse().setName(name), null),
											Format.TEXT, f -> f.fill())//
										.addHPanel("Speed:",
											new JustifiedBoxLayout(
												false).setMainAlignment(JustifiedBoxLayout.Alignment.LEADING),
											speedPanel -> speedPanel//
												.addTextField("Economy:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().INT,
														account -> account.getUniverse().getEconomySpeed(),
														(account, speed) -> account.getUniverse().setEconomySpeed(speed), null),
													SpinnerFormat.INT, f -> f.withPostLabel("x").modifyEditor(tf -> tf.withColumns(2)))//
												.spacer(3)//
												.addTextField("Research:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().INT,
														account -> account.getUniverse().getResearchSpeed(),
														(account, speed) -> account.getUniverse().setResearchSpeed(speed), null),
													SpinnerFormat.INT, f -> f.withPostLabel("x").modifyEditor(tf -> tf.withColumns(2)))//
												.spacer(3)//
												.addTextField("Fleet:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().INT,
														account -> account.getUniverse().getFleetSpeed(),
														(account, speed) -> account.getUniverse().setFleetSpeed(speed), null),
													SpinnerFormat.INT, f -> f.withPostLabel("x").modifyEditor(tf -> tf.withColumns(2)))//
										)//
										.addHPanel("Galaxies:",
											new JustifiedBoxLayout(false).setMainAlignment(JustifiedBoxLayout.Alignment.LEADING),
											galaxyPanel -> galaxyPanel//
												.addTextField("Galaxies:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().INT,
														account -> account.getUniverse().getGalaxies(),
														(account, galaxies) -> account.getUniverse().setGalaxies(galaxies), null), //
													SpinnerFormat.INT, tf -> tf.modifyEditor(tf2 -> tf2.withColumns(1)))//
												.addCheckField("Circular Universe:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().BOOLEAN,
														account -> account.getUniverse().isCircularUniverse(),
														(account, circular) -> account.getUniverse().setCircularUniverse(circular), null),
													null)//
												.addCheckField("Circular Galaxies:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().BOOLEAN,
														account -> account.getUniverse().isCircularGalaxies(),
														(account, circular) -> account.getUniverse().setCircularGalaxies(circular), null),
													null)//
										)//
										.addComboField("Account Class:",
											theSelectedAccount.asFieldEditor(TypeTokens.get().of(AccountClass.class), Account::getGameClass,
												Account::setGameClass, null),
											ObservableCollection.of(TypeTokens.get().of(AccountClass.class), AccountClass.values()), //
											classEditor -> classEditor.fill().withValueTooltip(clazz -> describeClass(clazz))//
										)//
										.addHPanel("Collector Bonus:", "box",
											collectorBonusPanel -> collectorBonusPanel//
												.addTextField("Production:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().INT,
														account -> account.getUniverse().getCollectorProductionBonus(),
														(account, speed) -> account.getUniverse().setCollectorProductionBonus(speed), null),
													SpinnerFormat.INT, f -> f.withPostLabel("%").modifyEditor(tf -> tf.withColumns(2)))//
												.spacer(4)//
												.addTextField("Energy:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().INT,
														account -> account.getUniverse().getCollectorEnergyBonus(),
														(account, speed) -> account.getUniverse().setCollectorEnergyBonus(speed), null),
													SpinnerFormat.INT, f -> f.withPostLabel("%").modifyEditor(tf -> tf.withColumns(2)))//
										)//
										.addTextField("Hyperspace Cargo Bonus:",
											theSelectedAccount.asFieldEditor(TypeTokens.get().DOUBLE,
												account -> account.getUniverse().getHyperspaceCargoBonus(),
												(account, speed) -> account.getUniverse().setHyperspaceCargoBonus(speed), null),
											SpinnerFormat.doubleFormat("0.##", 1), f -> f.withPostLabel("%").fill())//
										.addHPanel("Trade Ratios:", "box",
											tradeRatePanel -> tradeRatePanel//
												.addTextField(null,
													theSelectedAccount.asFieldEditor(TypeTokens.get().DOUBLE,
														account -> account.getUniverse().getTradeRatios().getMetal(),
														(account, rate) -> account.getUniverse().getTradeRatios().setMetal(rate), null),
													SpinnerFormat.doubleFormat("0.0#", 0.1), f -> f.modifyEditor(tf -> tf.withColumns(3)))
												.addComponent(null, new JLabel(":"), null)//
												.addTextField(null,
													theSelectedAccount.asFieldEditor(TypeTokens.get().DOUBLE,
														account -> account.getUniverse().getTradeRatios().getCrystal(),
														(account, rate) -> account.getUniverse().getTradeRatios().setCrystal(rate), null),
													SpinnerFormat.doubleFormat("0.0#", 0.1), f -> f.modifyEditor(tf -> tf.withColumns(3)))
												.addComponent(null, new JLabel(":"), null)//
												.addTextField(null,
													theSelectedAccount.asFieldEditor(TypeTokens.get().DOUBLE,
														account -> account.getUniverse().getTradeRatios().getDeuterium(),
														(account, rate) -> account.getUniverse().getTradeRatios().setDeuterium(rate), null),
													SpinnerFormat.doubleFormat("0.0#", 0.1), f -> f.modifyEditor(tf -> tf.withColumns(3)))//
										)//
										.addHPanel("Officers:", "box",
											officersPanel -> officersPanel//
												.addCheckField("Commander:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().BOOLEAN,
														account -> account.getOfficers().isCommander(),
														(account, b) -> account.getOfficers().setCommander(b), null),
													null)
												.spacer(3)//
												.addCheckField("Admiral:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().BOOLEAN,
														account -> account.getOfficers().isAdmiral(),
														(account, b) -> account.getOfficers().setAdmiral(b), null),
													null)
												.spacer(3)//
												.addCheckField("Engineer:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().BOOLEAN,
														account -> account.getOfficers().isEngineer(),
														(account, b) -> account.getOfficers().setEngineer(b), null),
													null)
												.spacer(3)//
												.addCheckField("Geologist:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().BOOLEAN,
														account -> account.getOfficers().isGeologist(),
														(account, b) -> account.getOfficers().setGeologist(b), null),
													null)
												.spacer(3)//
												.addCheckField("Technocrat:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().BOOLEAN,
														account -> account.getOfficers().isTechnocrat(),
														(account, b) -> account.getOfficers().setTechnocrat(b), null),
													null)
												.spacer(3)//
												.addCheckField("Commanding Staff:",
													theSelectedAccount.asFieldEditor(TypeTokens.get().BOOLEAN,
														account -> account.getOfficers().isCommandingStaff(),
														(account, b) -> account.getOfficers().setCommandingStaff(b), null),
													null)
												.spacer(3)//
										)//
										.addHPanel("Import Empire View:", "box", empirePanel -> empirePanel//
											.addButton("Planet View", this::browsePlanetFile, pv -> pv
												.withText(thePlanetEmpireFile.map(file -> file == null ? "Planet View" : file.getName()))
												.withTooltip("<html><ul>"//
													+ "<li>Select your Empire View</li>"//
													+ "<li>Save the page (e.g. Ctrl+S)</li>"//
													+ "<li>Choose the HTML Only option, select a location, and click Save</li>"//
													+ "<li>Click this button and select the saved file</li>"//
													+ "</li></ul></html>"))//
											.addButton("Moon View", this::browseMoonFile,
												pv -> pv
													.withText(theMoonEmpireFile.map(file -> file == null ? "Moon View" : file.getName()))
													.withTooltip("<html><ul>"//
														+ "<li>Select your Empire View</li>"//
														+ "<li>Select the \"Moons\" tab</li>"//
														+ "<li>Save the page (e.g. Ctrl+S)</li>"//
														+ "<li>Choose the HTML Only option, select a location, and click Save</li>"//
														+ "<li>Click this button and select the saved file</li>"//
														+ "</li></ul></html>"))//
											.addButton("Import", this::importEmpireView, pv -> pv.disableWith(thePlanetEmpireFile
												.map(f -> f == null ? "Download and select the planet empire view file" : null)))//
										)//
										.addHPanel("Import Overview:", "box",
											empirePanel -> empirePanel//
												.addButton("Import", this::importOverview,
													pv -> pv.withTooltip("<html><ul>"//
														+ "<li>Select your Overview</li>"//
														+ "<li>Save the page (e.g. Ctrl+S)</li>"//
														+ "<li>Choose the HTML Only option, select a location, and click Save</li>"//
														+ "<li>Click this button and select the saved file</li>"//
														+ "</li></ul></html>"))//
										)//
										.addButton("Test ROI",
											__ -> new OGameRoiSettings(theSelectedRuleSet.get(), theSelectedAccount.get()).test(15), null)//
									, acctSettingsTab -> acctSettingsTab.setName("Settings"))//
								.withVTab("planets", acctBuildingsPanel -> thePlanetPanel.addPlanetTable(acctBuildingsPanel)//
									, acctBuildingsTab -> acctBuildingsTab.setName("Builds"))//
								.withVTab("production", productionPanel -> theProductionPanel.addPanel(productionPanel), //
									productionTab -> productionTab.setName("Production"))//
								.withVTab("resSettings", resSettingsPanel -> theResourceSettingsPanel.addPanel(resSettingsPanel), //
									resSettingsTab -> resSettingsTab.setName("Resource Settings"))//
								.withVTab("upgrades", upgradesPanel -> theUpgradePanel.addPanel(upgradesPanel), //
									upgradesTab -> upgradesTab.setName("Upgrades"))//
								// .withVTab("construction",
								// constructionPanel -> new ConstructionPanel(theConfig, theSelectedRuleSet, theSelectedAccount)
								// .addPanel(constructionPanel),
								// constructionTab -> constructionTab.setName("Construction"))//
								.withVTab("resources", resPanel -> theHoldingsPanel.addPanel(resPanel),
									resTab -> resTab.setName("Resources"))//
								.withVTab("flights", theFlightPanel::addFlightPanel, flightsTab -> flightsTab.setName("Flights"))))//
		);
	}

	static class SimpleLineBorder extends LineBorder {
		public SimpleLineBorder(Color color, int thickness) {
			super(color, thickness);
		}

		void setColor(Color color) {
			lineColor = color;
		}
	}

	public static <M> void decorateDiffColumn(CategoryRenderStrategy<M, Integer> column, IntFunction<Integer> goal) {
		column.withRenderer(new ObservableCellRenderer.DefaultObservableCellRenderer<M, Integer>((m, c) -> c == null ? "" : c.toString()) {
			@Override
			public Component getCellRendererComponent(Component parent, ModelCell<M, Integer> cell, CellRenderContext ctx) {
				Component c = super.getCellRendererComponent(parent, cell, ctx);
				Integer value = cell.getCellValue();
				if (value == null) {
					return c;
				}
				Integer goalValue = goal.apply(cell.getRowIndex());
				if (goalValue != null && !value.equals(goalValue)) {
					StringBuilder str = new StringBuilder().append(value).append('/').append(goalValue);
					((JLabel) c).setText(str.toString());
				}
				return c;
			}
		}).decorate((cell, decorator) -> {
			Integer value = cell.getCellValue();
			if (value == null) {
				return;
			}
			Integer refValue = goal.apply(cell.getRowIndex());
			if (refValue == null || value.equals(refValue)) {
				return;
			}
			int diff = value - refValue;
			Color fg;
			if (diff < 0) {
				if (cell.isSelected() || diff < -3) {
					fg = Color.red;
				} else {
					int gb = 255 - (int) Math.round((4.0 + diff) * 255 / 4);
					fg = new Color(gb, 0, 0);
				}
			} else {
				if (cell.isSelected() || diff > 3) {
					fg = Color.green;
				} else {
					int rb = 255 - (int) Math.round((4.0 - diff) * 255 / 4);
					fg = new Color(0, rb, 0);
				}
			}
			decorator.withForeground(fg);
		});
	}

	static <T> CategoryRenderStrategy<Account, T> accountColumn(String name, Class<T> type, Function<Account, T> getter,
		BiConsumer<Account, T> setter, int width) {
		CategoryRenderStrategy<Account, T> column = new CategoryRenderStrategy<Account, T>(name, TypeTokens.get().of(type), getter);
		column.withWidths(width, width, width);
		if (setter != null) {
			column.withMutation(m -> m.mutateAttribute((p, v) -> setter.accept(p, v)).withRowUpdate(true));
		}
		return column;
	}

	static CategoryRenderStrategy<Account, Integer> intAccountColumn(String name, Function<Account, Integer> getter,
		BiConsumer<Account, Integer> setter, int width) {
		CategoryRenderStrategy<Account, Integer> column = accountColumn(name, int.class, getter, setter, width);
		if (setter != null) {
			column.withMutation(
				m -> m.asText(SpinnerFormat.INT).clicks(1).filterAccept((p, value) -> value >= 0 ? null : "Must not be negative"));
		}
		return column;
	}

	String printPoints(Account account, PointType type) {
		if (account.getPlanets().getValues().isEmpty()) {
			return "0";
		}
		UpgradeCost cost = UpgradeCost.ZERO;
		OGameRuleSet rules = theSelectedRuleSet.get();
		for (AccountUpgradeType upgrade : AccountUpgradeType.values()) {
			switch (upgrade.type) {
			case Building:
				for (Planet planet : account.getPlanets().getValues()) {
					cost = cost
						.plus(rules.economy().getUpgradeCost(account, planet, upgrade, 0, planet.getBuildingLevel(upgrade.building)));
				}
				break;
			case ShipyardItem:
				for (Planet planet : account.getPlanets().getValues()) {
					cost = cost
						.plus(rules.economy().getUpgradeCost(account, planet, upgrade, 0, planet.getStationedShips(upgrade.shipyardItem)));
				}
				break;
			case Research:
				cost = cost.plus(rules.economy().getUpgradeCost(account, account.getPlanets().getValues().getFirst(), upgrade, 0,
					account.getResearch().getResearchLevel(upgrade.research)));
				break;
			}
		}
		double value = cost.getPoints(type);
		value /= 1E3;
		return OGameUtils.printResourceAmount(value);
	}

	private static final Duration WEEK = Duration.ofDays(7);

	static String printUpgradeTime(Duration upgradeTime) {
		if (upgradeTime == null) {
			return "";
		}
		if (upgradeTime.compareTo(WEEK) < 0) {
			return QommonsUtils.printDuration(upgradeTime, true);
		} else {
			int wks = (int) (upgradeTime.getSeconds() / WEEK.getSeconds());
			Duration days = Duration.ofSeconds(upgradeTime.getSeconds() % WEEK.getSeconds(), upgradeTime.getNano());
			return wks + "w " + QommonsUtils.printDuration(days, true);
		}
	}

	int getNewId() {
		int id = 1;
		boolean found = true;
		while (found) {
			found = false;
			for (Account account : theAccounts.getValues()) {
				if (account.getId() == id) {
					found = true;
					break;
				}
			}
			if (found) {
				id++;
			}
		}
		return id;
	}

	static String describeClass(AccountClass clazz) {
		switch (clazz) {
		case Unselected:
			return "No class bonuses";
		case Collector:
			return "<ul>" + "<li>Can produce crawlers</li>" + "<li>+25% mine production</li>" + "<li>+10% energy production</li>"
				+ "<li>+100% speed for Transporters</li>" + "<li>+25% cargo bay for Transporters</li>" + "<li>+2 offers</li>"
				+ "<li>Lower Market Fees</li>" + "<li>+50% Crawler bonus</li>" + "</ul>";
		case General:
			return "<ul>" + "<li>Can produce reapers</li>" + "<li>+100% speed for combat ships</li>" + "<li>+100% speed for Recyclers</li>"
				+ "<li>-25% deuterium consumption for all ships</li>" + "<li>-25% deuterium consumption for Recyclers</li>"
				+ "<li>A small chance to immediately destroy a Deathstar once in a battle using a light fighter.</li>"
				+ "<li>Wreckage at attack (transport to starting planet)</li>" + "<li>+2 combat research levels</li>"
				+ "<li>+2 fleet slots</li></li>" + "</ul>";
		case Discoverer:
			return "<ul>" + "<li>Can produce pathfinders</li>" + "<li>-25% research time</li>"
				+ "<li>+2% gain on successful expeditions</li>" + "<li>+10% larger planets on colonisation</li>"
				+ "<li>Debris fields created on expeditions will be visible in the Galaxy view.</li>" + "<li>+2 expeditions</li>"
				+ "<li>+20% phalanx range</li>" + "<li>75% loot from inactive players</li>" + "</ul>";
		}
		return null;
	}

	static Account initAccount(Account account) {
		account.getUniverse().setName("");
		account.getUniverse().setCollectorProductionBonus(25);
		account.getUniverse().setCollectorEnergyBonus(10);
		account.getUniverse().setCrawlerCap(8);
		account.getUniverse().setEconomySpeed(1);
		account.getUniverse().setResearchSpeed(1);
		account.getUniverse().setFleetSpeed(1);
		account.getUniverse().setHyperspaceCargoBonus(5);
		account.getUniverse().getTradeRatios().setMetal(2.5);
		account.getUniverse().getTradeRatios().setCrystal(1.5);
		account.getUniverse().getTradeRatios().setDeuterium(1);
		account.getUniverse().setGalaxies(9).setCircularGalaxies(true).setCircularUniverse(true);
		account.setGameClass(AccountClass.Unselected);
		return account;
	}

	static Account copy(Account source, Account dest) {
		dest.setGameClass(source.getGameClass());

		dest.getUniverse().setName(source.getUniverse().getName());
		dest.getUniverse().setCollectorProductionBonus(source.getUniverse().getCollectorProductionBonus());
		dest.getUniverse().setCollectorEnergyBonus(source.getUniverse().getCollectorEnergyBonus());
		dest.getUniverse().setCrawlerCap(source.getUniverse().getCrawlerCap());
		dest.getUniverse().setEconomySpeed(source.getUniverse().getEconomySpeed());
		dest.getUniverse().setResearchSpeed(source.getUniverse().getResearchSpeed());
		dest.getUniverse().setFleetSpeed(source.getUniverse().getFleetSpeed());
		dest.getUniverse().setHyperspaceCargoBonus(source.getUniverse().getHyperspaceCargoBonus());
		dest.getUniverse().getTradeRatios().setMetal(source.getUniverse().getTradeRatios().getMetal());
		dest.getUniverse().getTradeRatios().setCrystal(source.getUniverse().getTradeRatios().getCrystal());
		dest.getUniverse().getTradeRatios().setDeuterium(source.getUniverse().getTradeRatios().getDeuterium());

		dest.getOfficers().setCommander(source.getOfficers().isCommander());
		dest.getOfficers().setAdmiral(source.getOfficers().isAdmiral());
		dest.getOfficers().setEngineer(source.getOfficers().isEngineer());
		dest.getOfficers().setGeologist(source.getOfficers().isGeologist());
		dest.getOfficers().setTechnocrat(source.getOfficers().isTechnocrat());
		dest.getOfficers().setCommandingStaff(source.getOfficers().isCommandingStaff());

		dest.getResearch().setEnergy(source.getResearch().getEnergy());
		dest.getResearch().setLaser(source.getResearch().getLaser());
		dest.getResearch().setIon(source.getResearch().getIon());
		dest.getResearch().setHyperspace(source.getResearch().getHyperspace());
		dest.getResearch().setPlasma(source.getResearch().getPlasma());
		dest.getResearch().setCombustionDrive(source.getResearch().getCombustionDrive());
		dest.getResearch().setImpulseDrive(source.getResearch().getImpulseDrive());
		dest.getResearch().setHyperspaceDrive(source.getResearch().getHyperspaceDrive());
		dest.getResearch().setEspionage(source.getResearch().getEspionage());
		dest.getResearch().setComputer(source.getResearch().getComputer());
		dest.getResearch().setAstrophysics(source.getResearch().getAstrophysics());
		dest.getResearch().setIntergalacticResearchNetwork(source.getResearch().getIntergalacticResearchNetwork());
		dest.getResearch().setGraviton(source.getResearch().getGraviton());
		dest.getResearch().setWeapons(source.getResearch().getWeapons());
		dest.getResearch().setShielding(source.getResearch().getShielding());
		dest.getResearch().setArmor(source.getResearch().getArmor());

		for (Planet srcP : source.getPlanets().getValues()) {
			Planet destP = dest.getPlanets().create()//
				.with(Planet::getName, srcP.getName())//
				.with(Planet::getBaseFields, srcP.getBaseFields())//

				.with(Planet::getMinimumTemperature, srcP.getMinimumTemperature())//
				.with(Planet::getMaximumTemperature, srcP.getMaximumTemperature())//

				.with(Planet::getMetalUtilization, srcP.getMetalUtilization())//
				.with(Planet::getCrystalUtilization, srcP.getCrystalUtilization())//
				.with(Planet::getDeuteriumUtilization, srcP.getDeuteriumUtilization())//
				.with(Planet::getSolarPlantUtilization, srcP.getSolarPlantUtilization())//
				.with(Planet::getFusionReactorUtilization, srcP.getFusionReactorUtilization())//
				.with(Planet::getSolarSatelliteUtilization, srcP.getSolarSatelliteUtilization())//
				.with(Planet::getCrawlerUtilization, srcP.getCrawlerUtilization())//

				.with(Planet::getMetalBonus, srcP.getMetalBonus())//
				.with(Planet::getCrystalBonus, srcP.getCrystalBonus())//
				.with(Planet::getDeuteriumBonus, srcP.getDeuteriumBonus())//
				.create().get();

			for (BuildingType type : BuildingType.values()) {
				destP.setBuildingLevel(type, srcP.getBuildingLevel(type));
				destP.getMoon().setBuildingLevel(type, srcP.getMoon().getBuildingLevel(type));
			}
			for (ShipyardItemType type : ShipyardItemType.values()) {
				destP.setStationedShips(type, srcP.getStationedShips(type));
				destP.getMoon().setStationedShips(type, srcP.getMoon().getStationedShips(type));
			}
		}

		return dest;
	}

	private void browsePlanetFile(Object cause) {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setFileFilter(new FileNameExtensionFilter("HTML Files", "html"));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			thePlanetEmpireFile.set(null, null);
			return;
		}
		thePlanetEmpireFile.set(chooser.getSelectedFile(), null);
	}

	private void browseMoonFile(Object cause) {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setFileFilter(new FileNameExtensionFilter("HTML Files", "html"));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			theMoonEmpireFile.set(null, null);
			return;
		}
		theMoonEmpireFile.set(chooser.getSelectedFile(), null);
	}

	private void importEmpireView(Object cause) {
		int planets;
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(thePlanetEmpireFile.get())))) {
			planets = OGamePageReader.readEmpireView(theSelectedAccount.get(), reader, theSelectedRuleSet.get(),
				() -> createPlanet().planet);
		} catch (IOException | RuntimeException e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(this, "Empire view parsing failed", "Unable to Import Empire View", JOptionPane.ERROR_MESSAGE);
			return;
		}

		if (theMoonEmpireFile.get() != null) {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(theMoonEmpireFile.get())))) {
				OGamePageReader.readEmpireMoonView(theSelectedAccount.get(), theSelectedRuleSet.get(), reader);
			} catch (IOException | RuntimeException e) {
				e.printStackTrace();
				JOptionPane.showMessageDialog(this, "Moon empire view parsing failed", "Unable to Import Moon Empire View",
					JOptionPane.ERROR_MESSAGE);
			}
		}
		theSelectedAccount.set(theSelectedAccount.get(), null);
		JOptionPane.showMessageDialog(this, "Successfully imported " + planets + " planets from Empire View", "Empire View Imported",
			JOptionPane.INFORMATION_MESSAGE);
	}

	private void importOverview(Object cause) {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setFileFilter(new FileNameExtensionFilter("HTML Files", "html"));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		int planets;
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(new FileInputStream(chooser.getSelectedFile()), Charset.forName("UTF-8")))) {
			planets = OGamePageReader.readOverview(theSelectedAccount.get(), reader, theSelectedRuleSet.get(), () -> createPlanet().planet);
		} catch (IOException | RuntimeException e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(this, "Overview parsing failed", "Unable to Import Overview", JOptionPane.ERROR_MESSAGE);
			return;
		}
		theSelectedAccount.set(theSelectedAccount.get(), null);
		JOptionPane.showMessageDialog(this, "Successfully imported " + planets + " planets from Overview", "Overview Imported",
			JOptionPane.INFORMATION_MESSAGE);
	}

	public static void main(String[] args) {
		List<OGameRuleSet> ruleSets = new ArrayList<>();
		ruleSets.addAll(Arrays.asList(//
			new OGameRuleSet710(), new OGameRuleSet711(), new OGameRuleSet750()));
		ObservableSwingUtils.buildUI()//
			.withConfig("ogame-config").withConfigAt("OGameUI.xml")//
			.withTitle("OGame Account Helper").systemLandF().build(config -> {
				return new OGameUniGui(config, ruleSets, getAccounts(config, "accounts/account"));
			});
	}

	public static SyncValueSet<Account> getAccounts(ObservableConfig config, String path) {
		ValueHolder<SyncValueSet<Account>> accounts = new ValueHolder<>();
		// Old code from when goals were configured as a referenced account
		// ObservableConfigFormat<Account> accountRefFormat = ObservableConfigFormat
		// .<Account> buildReferenceFormat(fv -> accounts.get().getValues(), null)//
		// .withField("id", Account::getId, ObservableConfigFormat.INT).build();
		config.asValue(TypeTokens.get().of(Account.class))
			.at(path).buildEntitySet(accounts);
		return accounts.get();
	}

	public class PlannedAccountUpgrade {
		final PlannedUpgrade planned;
		private final Planet thePlanet;
		private int theFrom;
		private Boolean isPaid;
		private UpgradeCost cost;
		private Duration roi;

		PlannedAccountUpgrade(PlannedUpgrade upgrade) {
			this.planned = upgrade;
			Planet planet = null;
			if (planned != null) {
				if (planned.getType().research == null) {
					for (Planet p : getSelectedAccount().get().getPlanets().getValues()) {
						if (p.getId() == planned.getPlanet()) {
							planet = p;
							break;
						}
					}
				}
			}
			thePlanet = planet;
			clear();
		}

		void clear() {
			theFrom = -1;
			cost = null;
			roi = null;
			isPaid = null;
		}

		PlannedUpgrade getUpgrade() {
			return planned;
		}

		Planet getPlanet() {
			return thePlanet;
		}

		int getFrom() {
			if (theFrom >= 0 || planned == null) {
				return theFrom;
			}
			int level = planned.getType().getLevel(getSelectedAccount().get(), thePlanet);
			for (PlannedUpgrade upgrade : getSelectedAccount().get().getPlannedUpgrades().getValues()) {
				if (upgrade == planned) {
					break;
				} else if (upgrade.getType() == planned.getType()//
					&& upgrade.getPlanet() == planned.getPlanet()//
					&& upgrade.isMoon() == planned.isMoon()) {
					level += upgrade.getQuantity();
				}
			}
			theFrom = level;
			return theFrom;
		}

		int getTo() {
			if (planned == null) {
				return 0;
			}
			return getFrom() + planned.getQuantity();
		}

		boolean isPaid() {
			if (planned == null) {
				return false;
			}
			if (isPaid == null) {
				Account account = getSelectedAccount().get();
				boolean alreadyPaid = false;
				if (getFrom() == planned.getType().getLevel(account, thePlanet)) {
					if (account.getResearch().getCurrentUpgrade() == planned.getType().research//
						|| (thePlanet != null && thePlanet.getCurrentUpgrade() == planned.getType().building)) {
						alreadyPaid = true;
					}
				}
				isPaid = alreadyPaid;
			}
			return isPaid;
		}

		UpgradeCost getCost() {
			if (cost == null && planned != null && !isPaid()) {
				cost = getRules().get().economy().getUpgradeCost(getSelectedAccount().get(), //
					thePlanet == null ? null : theUpgradeAccount.get().getPlanets().getUpgradePlanet(thePlanet), planned.getType(),
					getFrom(), theFrom + planned.getQuantity());
			}
			return cost;
		}

		Duration getROI() {
			if (roi == null && planned != null && !isPaid()) {
				roi = OGameUniGui.getROI(getRules().get(), getSelectedAccount().get(), this);
			}
			return roi;
		}

		@Override
		public String toString() {
			StringBuilder msg = new StringBuilder();
			if (thePlanet != null) {
				msg.append(thePlanet.getName()).append(' ');
			}
			if (getUpgrade() == null) {
				if (msg.length() > 0) {
					msg.append(' ');
				}
				return msg.append("Total").toString();
			}
			return msg.append(getUpgrade().getType()).append(' ').append(getFrom()).append("->").append(getTo()).toString();
		}
	}

	static Duration getROI(OGameRuleSet rules, Account account, PlannedAccountUpgrade upgrade) {
		if (upgrade.getUpgrade().getQuantity() <= 0) {
			return null;
		}
		switch (upgrade.getUpgrade().getType().type) {
		case Research:
			switch (upgrade.getUpgrade().getType().research) {
			case Astrophysics:
			case Plasma:
				break;
			case Energy: // TODO Involves adjusting usages, maybe will do it later
			default:
				return null;
			}
			break;
		case ShipyardItem:
			switch (upgrade.getUpgrade().getType().shipyardItem) {
			case Crawler:
			case SolarSatellite:
				break;
			default:
				return null;
			}
			break;
		case Building:
			switch (upgrade.getUpgrade().getType().building) {
			case MetalMine:
			case CrystalMine:
			case DeuteriumSynthesizer:
			case SolarPlant:
			case FusionReactor: // TODO Technically should adjust usages to calculate the real ROI, but that's tricky
				break;
			default:
				return null;
			}
			break;
		}

		Planet planet = upgrade.getPlanet();
		int planetIdx = account.getPlanets().getValues().indexOf(planet);
		double currentProduction = 0, newProduction = 0, cost = 0;
		TradeRatios tr = account.getUniverse().getTradeRatios();
		switch (upgrade.getUpgrade().getType().type) {
		case Research:
			List<FullProduction> productions = QommonsUtils.map(account.getPlanets().getValues(), //
				p -> rules.economy().getFullProduction(account, p), false);
			cost = upgrade.getCost().getMetalValue(tr);
			for (FullProduction p : productions) {
				currentProduction += p.asCost().getMetalValue(tr);
			}
			switch (upgrade.getUpgrade().getType().research) {
			case Astrophysics:
				int newPlanets = (upgrade.getTo() + 1) / 2 + 1;
				newProduction = currentProduction * newPlanets / productions.size();
				double planetCost = 0;
				for (AccountUpgradeType u : AccountUpgradeType.values()) {
					switch (u.type) {
					case Building:
						for (Planet p : account.getPlanets().getValues()) {
							planetCost += rules.economy().getUpgradeCost(account, p, u, 0, p.getBuildingLevel(u.building))
								.getMetalValue(tr);
						}
						break;
					case ShipyardItem:
						if (u.shipyardItem.mobile) {
							continue;
						}
						for (Planet p : account.getPlanets().getValues()) {
							planetCost += rules.economy().getUpgradeCost(account, p, u, 0, p.getStationedShips(u.shipyardItem))
								.getMetalValue(tr);
						}
						break;
					default:
						break;
					}
				}
				cost += planetCost / productions.size();
				break;
			case Plasma:
				if (upgrade.getPlanet() == null) {
					UpgradeAccount upgradeAccount = new UpgradeAccount(account).withUpgrade(upgrade.getUpgrade());
					newProduction = 0;
					for (Planet p : account.getPlanets().getValues()) {
						newProduction += rules.economy().getFullProduction(upgradeAccount, p).asCost().getMetalValue(tr);
					}
				}
				break;
			default:
				return null;
			}
			break;
		default:
			productions = QommonsUtils.map(account.getPlanets().getValues(), //
				p -> rules.economy().getFullProduction(account, p), false);
			cost = upgrade.getCost().getMetalValue(tr);
			newProduction = currentProduction - productions.get(planetIdx).asCost().getMetalValue(tr);
			UpgradeAccount upgradeAccount = new UpgradeAccount(account).withUpgrade(upgrade.getUpgrade());
			UpgradeAccount.UpgradePlanet upgradePlanet = upgradeAccount.getPlanets().getUpgradePlanet(planet);
			newProduction += rules.economy().getFullProduction(upgradeAccount, upgradePlanet).asCost().getMetalValue(tr);
			break;
		}
		if (newProduction <= currentProduction) {
			return null;
		}
		double seconds = cost / (newProduction - currentProduction) * 3600;
		return Duration.ofSeconds((long) seconds);
	}
}
