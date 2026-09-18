Name:           thumbtrek
Version:        0.5.0
Release:        1%{?dist}
Summary:        Strava for scrolling — offline-first scroll tracker for Linux
License:        MIT
URL:            https://github.com/Aditya190803/ThumbTrek
Source0:        https://github.com/Aditya190803/ThumbTrek/releases/download/v%{version}/thumbtrek-linux-%{version}.tar.gz
BuildArch:      noarch
Requires:       python3 python3-gobject gtk4 libadwaita
BuildRequires:  python3

%description
Offline-first scroll tracker for Linux desktops. Daemon batches mouse-wheel
motion into SQLite; CLI + localhost dashboard render streaks, history and the
clean-days limit game. Syncs to the shared ThumbTrek leaderboard over HTTPS
only after opt-in (source slug: linux).

%prep
%setup -q -n thumbtrek-linux-%{version}

%build
# Pure Python, nothing to compile.

%install
mkdir -p %{buildroot}%{_libdir}/thumbtrek %{buildroot}%{_bindir} \
  %{buildroot}%{_unitdir} %{buildroot}%{_datadir}/applications \
  %{buildroot}%{_datadir}/thumbtrek %{buildroot}%{_datadir}/fonts/thumbtrek
cp -r thumbtrek %{buildroot}%{_libdir}/thumbtrek/
install -m755 bin/thumbtrek %{buildroot}%{_libdir}/thumbtrek/thumbtrek-run
install -m755 bin/thumbtrek-native-host %{buildroot}%{_libdir}/thumbtrek/thumbtrek-native-host
cp -r extension %{buildroot}%{_datadir}/thumbtrek/extension
install -m644 assets/fonts/*.ttf %{buildroot}%{_datadir}/fonts/thumbtrek/
printf '#!/bin/sh\nexec /usr/bin/python3 %{_libdir}/thumbtrek/thumbtrek-run "$@"\n' \
  > %{buildroot}%{_bindir}/thumbtrek
chmod 755 %{buildroot}%{_bindir}/thumbtrek
install -m644 systemd/thumbtrek-tracker.service %{buildroot}%{_unitdir}/
install -m644 desktop/thumbtrek.desktop %{buildroot}%{_datadir}/applications/

%files
%{_libdir}/thumbtrek/
%{_bindir}/thumbtrek
%{_unitdir}/thumbtrek-tracker.service
%{_datadir}/applications/thumbtrek.desktop
%{_datadir}/thumbtrek/extension/
%{_datadir}/fonts/thumbtrek/

%changelog
* Fri Sep 18 2026 ThumbTrek - 0.5.0-1
- Initial Linux port (AUR-first, deb/rpm via CI).
