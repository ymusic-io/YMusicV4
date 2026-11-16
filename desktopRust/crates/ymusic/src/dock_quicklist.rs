//! The dock icon's menu on Linux (the macOS Dock menu's Pause/Resume and Stop): a
//! `com.canonical.dbusmenu` that the Unity LauncherEntry API points the dock at (Ubuntu's dock,
//! dash-to-dock). The dock adds its own Quit. One outbox thread serves the menu and publishes it.

use std::collections::HashMap;
use std::sync::mpsc;

use futures::channel::mpsc::UnboundedSender;
use serde::Serialize;
use ym_common::log::Logger;
use ym_i18n::{Str, t};
use zbus::blocking::Connection;
use zbus::blocking::object_server::InterfaceRef;
use zbus::interface;
use zbus::object_server::SignalEmitter;
use zbus::zvariant::{ObjectPath, OwnedValue, StructureBuilder, Type, Value};

use crate::desktop::TrayCommand;

const MENU_PATH: &str = "/io/ymusic/DockMenu";
const ENTRY_PATH: &str = "/io/ymusic/LauncherEntry";
const ROOT_ID: i32 = 0;
const TOGGLE_ID: i32 = 1;
const STOP_ID: i32 = 2;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct State {
    has_queue: bool,
    playing: bool,
}

pub struct DockQuicklist {
    outbox: mpsc::Sender<State>,
}

impl DockQuicklist {
    /// Publishes nothing until the first [`update`](Self::update).
    pub fn start(commands: UnboundedSender<TrayCommand>, runtime: tokio::runtime::Handle, log: Logger) -> Self {
        let (outbox, inbox) = mpsc::channel::<State>();
        let thread_log = log.clone();
        let started = std::thread::Builder::new().name("dock-quicklist".into()).spawn(move || {
            let _runtime = runtime.enter();
            let log = thread_log;
            let served = Connection::session().and_then(|connection| {
                let menu = {
                    let server = connection.object_server();
                    server.at(MENU_PATH, DbusMenu { state: State::default(), revision: 0, commands })?;
                    server.interface::<_, DbusMenu>(MENU_PATH)?
                };
                Ok((connection, menu))
            });
            let (connection, menu) = match served {
                Ok(served) => served,
                Err(error) => return log.warn("R90035 dock menu not served on the session bus", Some(&error)),
            };
            let app_uri = format!("application://{}.desktop", crate::desktop::desktop_entry());
            for state in inbox {
                if let Err(error) = publish(&connection, &menu, &app_uri, state) {
                    log.warn("R90036 dock menu update not published", Some(&error));
                }
            }
        });
        if let Err(error) = started {
            log.warn("R90035 dock menu not served on the session bus", Some(&error));
        }
        Self { outbox }
    }

    pub fn update(&self, has_queue: bool, playing: bool) {
        // The outbox ends only with this quicklist, or when the bus was unreachable (logged there).
        let _ = self.outbox.send(State { has_queue, playing });
    }
}

fn publish(connection: &Connection, menu: &InterfaceRef<DbusMenu>, app_uri: &str, state: State) -> zbus::Result<()> {
    let revision = {
        let mut iface = menu.get_mut();
        iface.state = state;
        iface.revision += 1;
        iface.revision
    };
    zbus::block_on(DbusMenu::layout_updated(menu.signal_emitter(), revision, ROOT_ID))?;
    // Every update re-announces the menu: a dock (re)started since knows of it only from this.
    let quicklist = HashMap::from([("quicklist", Value::from(ObjectPath::from_static_str_unchecked(MENU_PATH)))]);
    connection.emit_signal(
        None::<&str>,
        ENTRY_PATH,
        "com.canonical.Unity.LauncherEntry",
        "Update",
        &(app_uri, quicklist),
    )
}

struct DbusMenu {
    state: State,
    revision: u32,
    commands: UnboundedSender<TrayCommand>,
}

impl DbusMenu {
    fn items(&self) -> Vec<(i32, Str)> {
        if !self.state.has_queue {
            return Vec::new();
        }
        let toggle = if self.state.playing { Str::TrayPause } else { Str::TrayResume };
        vec![(TOGGLE_ID, toggle), (STOP_ID, Str::TrayStop)]
    }

    fn properties(&self, id: i32) -> Option<HashMap<String, OwnedValue>> {
        if id == ROOT_ID {
            return Some(HashMap::from([(
                "children-display".to_owned(),
                OwnedValue::from(zbus::zvariant::Str::from("submenu")),
            )]));
        }
        let (_, label) = self.items().into_iter().find(|(item, _)| *item == id)?;
        Some(HashMap::from([
            ("label".to_owned(), OwnedValue::from(zbus::zvariant::Str::from(t(label).to_owned()))),
            ("enabled".to_owned(), OwnedValue::from(true)),
            ("visible".to_owned(), OwnedValue::from(true)),
        ]))
    }

    fn layout(&self, id: i32, children: bool) -> Option<Layout> {
        let properties = self.properties(id)?;
        let children = if id == ROOT_ID && children {
            self.items().into_iter().filter_map(|(item, _)| self.layout(item, false)).map(Value::from).collect()
        } else {
            Vec::new()
        };
        Some(Layout { id, properties, children })
    }
}

/// A dbusmenu node, `(ia{sv}av)`; each child is one of these in a variant.
#[derive(Type, Serialize)]
struct Layout {
    id: i32,
    properties: HashMap<String, OwnedValue>,
    children: Vec<Value<'static>>,
}

impl From<Layout> for Value<'static> {
    fn from(layout: Layout) -> Self {
        let structure = StructureBuilder::new()
            .add_field(layout.id)
            .add_field(layout.properties)
            .add_field(layout.children)
            .build()
            .expect("a structure with fields");
        Value::from(structure)
    }
}

#[interface(name = "com.canonical.dbusmenu")]
impl DbusMenu {
    fn get_layout(
        &self,
        parent_id: i32,
        recursion_depth: i32,
        _property_names: Vec<String>,
    ) -> zbus::fdo::Result<(u32, Layout)> {
        let layout = self
            .layout(parent_id, recursion_depth != 0)
            .ok_or_else(|| zbus::fdo::Error::InvalidArgs(format!("no menu item {parent_id}")))?;
        Ok((self.revision, layout))
    }

    fn get_group_properties(
        &self,
        ids: Vec<i32>,
        _property_names: Vec<String>,
    ) -> Vec<(i32, HashMap<String, OwnedValue>)> {
        let ids = if ids.is_empty() {
            std::iter::once(ROOT_ID).chain(self.items().into_iter().map(|(id, _)| id)).collect()
        } else {
            ids
        };
        ids.into_iter().filter_map(|id| self.properties(id).map(|properties| (id, properties))).collect()
    }

    fn get_property(&self, id: i32, name: String) -> zbus::fdo::Result<OwnedValue> {
        self.properties(id)
            .and_then(|mut properties| properties.remove(&name))
            .ok_or_else(|| zbus::fdo::Error::InvalidArgs(format!("no property {name} on menu item {id}")))
    }

    fn event(&self, id: i32, event_id: String, _data: OwnedValue, _timestamp: u32) -> zbus::fdo::Result<()> {
        if event_id != "clicked" {
            return Ok(());
        }
        let command = match id {
            TOGGLE_ID => TrayCommand::TogglePlay,
            STOP_ID => TrayCommand::Stop,
            _ => return Err(zbus::fdo::Error::InvalidArgs(format!("no menu item {id}"))),
        };
        // The receiver lives as long as the shell.
        let _ = self.commands.unbounded_send(command);
        Ok(())
    }

    fn event_group(&self, events: Vec<(i32, String, OwnedValue, u32)>) -> zbus::fdo::Result<Vec<i32>> {
        let mut unknown = Vec::new();
        for (id, event_id, data, timestamp) in events {
            if self.event(id, event_id, data, timestamp).is_err() {
                unknown.push(id);
            }
        }
        Ok(unknown)
    }

    fn about_to_show(&self, _id: i32) -> bool {
        false
    }

    fn about_to_show_group(&self, _ids: Vec<i32>) -> (Vec<i32>, Vec<i32>) {
        (Vec::new(), Vec::new())
    }

    #[zbus(property)]
    fn version(&self) -> u32 {
        3
    }

    #[zbus(property)]
    fn text_direction(&self) -> String {
        "ltr".to_owned()
    }

    #[zbus(property)]
    fn status(&self) -> String {
        "normal".to_owned()
    }

    #[zbus(property)]
    fn icon_theme_path(&self) -> Vec<String> {
        Vec::new()
    }

    #[zbus(signal)]
    async fn layout_updated(emitter: &SignalEmitter<'_>, revision: u32, parent: i32) -> zbus::Result<()>;
}
