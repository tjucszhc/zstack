package org.zstack.test.integration.storage.primary.local.psmaintenance

import org.springframework.http.HttpEntity
import org.zstack.kvm.KVMConstant
import org.zstack.core.db.DatabaseFacade
import org.zstack.header.storage.primary.PrimaryStorageState
import org.zstack.header.storage.primary.PrimaryStorageStateEvent
import org.zstack.header.storage.primary.PrimaryStorageVO
import org.zstack.test.integration.storage.Env
import org.zstack.test.integration.storage.StorageTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.PrimaryStorageSpec
import org.zstack.testlib.ImageSpec 
import org.zstack.testlib.SubCase
import org.zstack.testlib.VmSpec
import org.zstack.compute.vm.VmGlobalConfig
import org.zstack.sdk.VmInstanceInventory
import org.zstack.sdk.HostInventory
import org.zstack.sdk.DiskOfferingInventory 
import org.zstack.sdk.VolumeInventory
import org.zstack.sdk.DetachDataVolumeFromVmAction
import org.zstack.storage.primary.local.LocalStorageKvmBackend
import org.zstack.storage.volume.VolumeGlobalConfig
import org.zstack.header.volume.VolumeDeletionPolicyManager

/**
 * Created by shengyan on 2017/3/22.
 */
class LocalStorageMaintenancePrimaryStorageDeleteAttachedSharedVolumeCase extends SubCase{
    EnvSpec env

    @Override
    void setup() {
        useSpring(StorageTest.springSpec)
    }

    @Override
    void environment() {
        env = Env.localStorageOneVmEnvForPrimaryStorage()
    }

    @Override
    void test() {
        env.create {
            testLocalStorageDeleteAttachedSharedVolumeWhenPrimaryStorageIsMaintained()
        }
    }

    void testLocalStorageDeleteAttachedSharedVolumeWhenPrimaryStorageIsMaintained() {
        VolumeGlobalConfig.VOLUME_DELETION_POLICY.updateValue(VolumeDeletionPolicyManager.VolumeDeletionPolicy.Delay.toString())
        PrimaryStorageSpec primaryStorageSpec = env.specByName("local")
        String imageUuid = (env.specByName("test-iso") as ImageSpec).inventory.uuid
        DatabaseFacade dbf = bean(DatabaseFacade.class)
        HostInventory host = env.inventoryByName("kvm")
        DiskOfferingInventory diskOfferingInventory = env.inventoryByName("diskOffering")
        VmInstanceInventory vm = env.inventoryByName("test-vm")

        VolumeInventory dataVolume = createDataVolume {
            name = "dataVolume"
            diskOfferingUuid = diskOfferingInventory.uuid
            primaryStorageUuid = primaryStorageSpec.inventory.uuid
            systemTags = ["localStorage::hostUuid::${host.uuid}".toString(), "ephemeral::shareable".toString(), "capability::virtio-scsi".toString()]
        }

        def attach_volume_path_is_invoked = false
        env.afterSimulator(KVMConstant.KVM_ATTACH_VOLUME) { rsp, HttpEntity<String> e ->
            attach_volume_path_is_invoked = true
            return rsp
        }
        attachDataVolumeToVm {
            vmInstanceUuid = vm.uuid
            volumeUuid = dataVolume.uuid
        }
        assert attach_volume_path_is_invoked


        changePrimaryStorageState {
            uuid = primaryStorageSpec.inventory.uuid
            stateEvent = PrimaryStorageStateEvent.maintain.toString()
        }
        assert dbf.findByUuid(primaryStorageSpec.inventory.uuid, PrimaryStorageVO.class).state == PrimaryStorageState.Maintenance
        

        def delete_bits_path_is_invoked = false
        env.afterSimulator(LocalStorageKvmBackend.DELETE_BITS_PATH) { rsp, HttpEntity<String> e ->
            delete_bits_path_is_invoked = true
            return rsp
        }
        deleteDataVolume {
            uuid = dataVolume.uuid
        }
        assert !delete_bits_path_is_invoked


        changePrimaryStorageState {
            uuid = primaryStorageSpec.inventory.uuid
            stateEvent = PrimaryStorageStateEvent.enable.toString()
        }
        assert dbf.findByUuid(primaryStorageSpec.inventory.uuid, PrimaryStorageVO.class).state == PrimaryStorageState.Enabled
    }

    @Override
    void clean() {
        env.delete()
    }
}
