package android.system.virtualmachine;

/** Compile-only stub. */
public class VirtualMachineManager {
    /** Bit flags of {@link #getCapabilities()}. */
    public static final int CAPABILITY_PROTECTED_VM = 1;
    public static final int CAPABILITY_NON_PROTECTED_VM = 2;
    private VirtualMachineManager() {}
    public int getCapabilities() { throw new RuntimeException("stub"); }
    public VirtualMachine create(String name, VirtualMachineConfig config) throws VirtualMachineException { throw new RuntimeException("stub"); }
    public VirtualMachine get(String name) throws VirtualMachineException { throw new RuntimeException("stub"); }
    public VirtualMachine getOrCreate(String name, VirtualMachineConfig config) throws VirtualMachineException { throw new RuntimeException("stub"); }
    public void delete(String name) throws VirtualMachineException { throw new RuntimeException("stub"); }
}
