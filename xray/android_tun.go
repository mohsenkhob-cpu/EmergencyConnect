//go:build android

package libXray

import (
	"os"
	"strconv"

	"github.com/xtls/libxray/xray"
)

// StartAndroidTun gives Xray the borrowed Android VpnService descriptor.
func StartAndroidTun(config string, fd int) error {
	if err := os.Setenv("xray.tun.fd", strconv.Itoa(fd)); err != nil {
		return err
	}
	defer os.Unsetenv("xray.tun.fd")
	return xray.RunXray(config)
}

func StopAndroidTun() error {
	return xray.StopXray()
}
