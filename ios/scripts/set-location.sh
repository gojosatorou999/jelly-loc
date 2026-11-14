#!/usr/bin/env bash
# Writes ios/Locations/Location.gpx — the location Xcode simulates on the iPhone.
# Usage: ./scripts/set-location.sh LAT LON ["Place name"]
# Example: ./scripts/set-location.sh 17.361563 78.474665 "Charminar"
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "Usage: $0 LAT LON [\"Place name\"]" >&2
  exit 1
fi

lat="$1"
lon="$2"
name="${3:-Jelly Loc}"
num='^-?[0-9]+(\.[0-9]+)?$'

if ! [[ "$lat" =~ $num && "$lon" =~ $num ]]; then
  echo "LAT and LON must be decimal numbers, e.g. 17.361563 78.474665" >&2
  exit 1
fi
if ! awk -v a="$lat" -v o="$lon" 'BEGIN { exit !(a >= -90 && a <= 90 && o >= -180 && o <= 180) }'; then
  echo "Out of range: LAT must be -90..90 and LON -180..180" >&2
  exit 1
fi

# Escape characters that are special in XML
name_xml=$(printf '%s' "$name" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g')

dir="$(cd "$(dirname "$0")/.." && pwd)"
cat > "$dir/Locations/Location.gpx" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Jelly Loc" xmlns="http://www.topografix.com/GPX/1/1">
  <wpt lat="$lat" lon="$lon">
    <name>$name_xml</name>
  </wpt>
</gpx>
EOF

echo "Location set to $name ($lat, $lon) in Locations/Location.gpx"
