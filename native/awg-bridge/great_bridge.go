package main

import (
    "errors"
    "math"
    "os"
    "sync"

    "github.com/amnezia-vpn/amneziawg-go/v3/conn"
    "github.com/amnezia-vpn/amneziawg-go/v3/device"
    "github.com/amnezia-vpn/amneziawg-go/v3/tun"
)

// greatBridgeTun is GREAT's packet boundary for the official AmneziaWG engine.
// It deliberately implements only tun.Device. The crypto/protocol engine remains
// the upstream amneziawg-go implementation.
type greatBridgeTun struct {
    file      *os.File
    name      string
    mtu       int
    events    chan tun.Event
    closeOnce sync.Once
}

func newGreatBridgeTun(file *os.File, name string, mtu int) *greatBridgeTun {
    return &greatBridgeTun{
        file:   file,
        name:   name,
        mtu:    mtu,
        events: make(chan tun.Event, 1),
    }
}

func (t *greatBridgeTun) File() *os.File { return t.file }

func (t *greatBridgeTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
    if len(bufs) == 0 || len(sizes) == 0 {
        return 0, errors.New("great bridge read called without buffers")
    }
    n, err := t.file.Read(bufs[0][offset:])
    if err != nil {
        return 0, err
    }
    sizes[0] = n
    return 1, nil
}

func (t *greatBridgeTun) Write(bufs [][]byte, offset int) (int, error) {
    total := 0
    for _, packet := range bufs {
        if offset > len(packet) {
            return total, errors.New("great bridge write offset exceeds packet")
        }
        n, err := t.file.Write(packet[offset:])
        total += n
        if err != nil {
            return total, err
        }
        if n != len(packet)-offset {
            return total, errors.New("great bridge short packet write")
        }
    }
    return total, nil
}

func (t *greatBridgeTun) MTU() (int, error) { return t.mtu, nil }
func (t *greatBridgeTun) Name() (string, error) { return t.name, nil }
func (t *greatBridgeTun) Events() <-chan tun.Event { return t.events }
func (t *greatBridgeTun) BatchSize() int { return 1 }

func (t *greatBridgeTun) Close() error {
    var err error
    t.closeOnce.Do(func() {
        close(t.events)
        err = t.file.Close()
    })
    return err
}

var (
    greatBridgeMu      sync.Mutex
    greatBridgeHandles = make(map[int32]*device.Device)
)

//export greatBridgeTurnOn
func greatBridgeTurnOn(interfaceName string, bridgeFd int32, mtu int32, settings string) int32 {
    if bridgeFd < 0 || mtu <= 0 {
        return -1
    }

    file := os.NewFile(uintptr(bridgeFd), "great-awg-bridge")
    if file == nil {
        return -1
    }

    bridge := newGreatBridgeTun(file, interfaceName, int(mtu))
    logger := device.NewLogger(device.LogLevelError, "GREAT/"+interfaceName)
    dev := device.NewDevice(bridge, conn.NewStdNetBind(), logger)

    if err := dev.IpcSet(settings); err != nil {
        dev.Close()
        return -1
    }
    dev.DisableSomeRoamingForBrokenMobileSemantics()
    if err := dev.Up(); err != nil {
        dev.Close()
        return -1
    }

    greatBridgeMu.Lock()
    defer greatBridgeMu.Unlock()
    for handle := int32(0); handle < math.MaxInt32; handle++ {
        if _, exists := greatBridgeHandles[handle]; !exists {
            greatBridgeHandles[handle] = dev
            return handle
        }
    }

    dev.Close()
    return -1
}

//export greatBridgeTurnOff
func greatBridgeTurnOff(handle int32) {
    greatBridgeMu.Lock()
    dev := greatBridgeHandles[handle]
    delete(greatBridgeHandles, handle)
    greatBridgeMu.Unlock()
    if dev != nil {
        dev.Close()
    }
}

func greatBridgeDevice(handle int32) *device.Device {
    greatBridgeMu.Lock()
    dev := greatBridgeHandles[handle]
    greatBridgeMu.Unlock()
    return dev
}

//export greatBridgeGetSocketV4
func greatBridgeGetSocketV4(handle int32) int32 {
    dev := greatBridgeDevice(handle)
    if dev == nil {
        return -1
    }
    bind, _ := dev.Bind().(conn.PeekLookAtSocketFd)
    if bind == nil {
        return -1
    }
    fd, err := bind.PeekLookAtSocketFd4()
    if err != nil {
        return -1
    }
    return int32(fd)
}

//export greatBridgeGetSocketV6
func greatBridgeGetSocketV6(handle int32) int32 {
    dev := greatBridgeDevice(handle)
    if dev == nil {
        return -1
    }
    bind, _ := dev.Bind().(conn.PeekLookAtSocketFd)
    if bind == nil {
        return -1
    }
    fd, err := bind.PeekLookAtSocketFd6()
    if err != nil {
        return -1
    }
    return int32(fd)
}
