#![allow(missing_docs, non_upper_case_globals, non_camel_case_types, unused_imports, dead_code, clippy::all)]

pub mod client {
    use wayland_client;
    use wayland_client::protocol::*;

    pub mod __interfaces {
        use wayland_client::protocol::__interfaces::*;
        wayland_scanner::generate_interfaces!("src/linux/wayland/gtk-shell.xml");
    }
    use self::__interfaces::*;

    wayland_scanner::generate_client_code!("src/linux/wayland/gtk-shell.xml");
}
