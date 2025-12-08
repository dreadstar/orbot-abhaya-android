import React from "react";

// Helper to create a file link
const fileLink = (path: string, label?: string) => (
  <a href={`file://${path}`} target="_blank" rel="noopener noreferrer" style={{ color: '#1976d2', textDecoration: 'underline' }}>{label || path}</a>
);

// Data for the UI graph (simplified for graph rendering)
const uiGraph = {
  activities: [
    {
      name: 'OrbotActivity',
      file: 'app/src/main/java/org/torproject/android/OrbotActivity.kt',
      nav: true,
    },
    {
      name: 'MainActivity',
      file: 'app/src/main/java/org/torproject/android/MainActivity.kt',
    },
    {
      name: 'AppManagerActivity',
      file: 'app/src/main/java/org/torproject/android/AppManagerActivity.kt',
    },
    {
      name: 'SettingsActivity',
      file: 'app/src/main/java/org/torproject/android/SettingsActivity.kt',
    },
  ],
  nav: {
    label: 'BottomNavigationView',
    file: 'app/src/main/res/layout/activity_orbot.xml',
    tabs: [
      {
        label: 'Connect',
        fragment: {
          label: 'ConnectFragment',
          file: 'app/src/main/java/org/torproject/android/ui/ConnectFragment.kt',
          objects: [
            { name: 'onCreateView', file: 'app/src/main/java/org/torproject/android/ui/ConnectFragment.kt' },
            { name: 'onViewCreated', file: 'app/src/main/java/org/torproject/android/ui/ConnectFragment.kt' },
            { name: 'ConnectViewModel', file: 'app/src/main/java/org/torproject/android/ui/ConnectViewModel.kt' },
          ],
        },
      },
      {
        label: 'Friends',
        fragment: {
          label: 'FriendsFragment',
          file: 'app/src/main/java/org/torproject/android/ui/FriendsFragment.kt',
          objects: [
            { name: 'onCreateView', file: 'app/src/main/java/org/torproject/android/ui/FriendsFragment.kt' },
            { name: 'onViewCreated', file: 'app/src/main/java/org/torproject/android/ui/FriendsFragment.kt' },
            { name: 'FriendsAdapter', file: 'app/src/main/java/org/torproject/android/ui/FriendsAdapter.kt' },
          ],
        },
      },
      {
        label: 'Kindness',
        fragment: {
          label: 'KindnessFragment',
          file: 'app/src/main/java/org/torproject/android/ui/KindnessFragment.kt',
          objects: [
            { name: 'onCreateView', file: 'app/src/main/java/org/torproject/android/ui/KindnessFragment.kt' },
            { name: 'showPanelStatus', file: 'app/src/main/java/org/torproject/android/ui/KindnessFragment.kt' },
          ],
        },
      },
      {
        label: 'Mesh',
        fragment: {
          label: 'EnhancedMeshFragment',
          file: 'app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt',
          objects: [
            { name: 'onCreateView', file: 'app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt' },
            { name: 'onViewCreated', file: 'app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt' },
            { name: 'handleSelectedFolder', file: 'app/src/main/java/org/torproject/android/ui/mesh/EnhancedMeshFragment.kt' },
            { name: 'DropFolderFragment', file: 'app/src/main/java/org/torproject/android/ui/mesh/DropFolderFragment.kt' },
            { name: 'StorageParticipationFragment', file: 'app/src/main/java/org/torproject/android/ui/mesh/StorageParticipationFragment.kt' },
            { name: 'MeshManagers', file: 'app/src/main/java/org/torproject/android/service/mesh/MeshManagers.kt' },
            { name: 'MeshTrafficRouter', file: 'app/src/main/java/org/torproject/android/service/interfaces/MeshTrafficRouter.kt' },
          ],
        },
      },
      {
        label: 'More',
        fragment: {
          label: 'MoreFragment',
          file: 'app/src/main/java/org/torproject/android/ui/MoreFragment.kt',
          objects: [
            { name: 'onAttach', file: 'app/src/main/java/org/torproject/android/ui/MoreFragment.kt' },
            { name: 'updateStatus', file: 'app/src/main/java/org/torproject/android/ui/MoreFragment.kt' },
            { name: 'SettingsPreferenceFragment', file: 'app/src/main/java/org/torproject/android/ui/SettingsPreferenceFragment.kt' },
            { name: 'AboutDialogFragment', file: 'app/src/main/java/org/torproject/android/ui/AboutDialogFragment.kt' },
            { name: 'CamoFragment', file: 'app/src/main/java/org/torproject/android/ui/CamoFragment.kt' },
            { name: 'CamoConfirmationDialogFragment', file: 'app/src/main/java/org/torproject/android/ui/CamoConfirmationDialogFragment.kt' },
          ],
        },
      },
    ],
  },
};

// Graph node component
const GraphNode: React.FC<{ label: React.ReactNode; children?: React.ReactNode; style?: React.CSSProperties; }> = ({ label, children, style }) => (
  <div style={{
    border: '2.5px solid #1976d2',
    borderRadius: 10,
    padding: 14,
    margin: 10,
    background: '#f5faff',
    minWidth: 180,
    display: 'inline-block',
    boxShadow: '0 2px 8px #0001',
    ...style,
  }}>
    {label}
    {children && <div style={{ marginTop: 10 }}>{children}</div>}
  </div>
);

// Render a fragment node with its code links
const FragmentGraph: React.FC<{ tab: any }> = ({ tab }) => (
  <GraphNode
    label={<div><b>{tab.fragment.label}</b><br />{fileLink(tab.fragment.file)}</div>}
    style={{ background: '#e3f2fd', minWidth: 220 }}
  >
    {tab.fragment.objects.map((obj: any, i: number) => (
      <div key={i} style={{ margin: '4px 0 4px 12px', fontSize: 13 }}>
        <span style={{ color: '#333' }}>{obj.name}</span>{' '}
        (<span style={{ color: '#1976d2' }}>{fileLink(obj.file)}</span>)
      </div>
    ))}
  </GraphNode>
);

// Render the navigation tabs as a horizontal graph
const NavGraph: React.FC = () => (
  <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'center', gap: 32, flexWrap: 'wrap', margin: '0 0 32px 0' }}>
    {uiGraph.nav.tabs.map((tab, i) => (
      <div key={i} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
        <div style={{ fontWeight: 600, color: '#1976d2', marginBottom: 4 }}>{tab.label}</div>
        <FragmentGraph tab={tab} />
      </div>
    ))}
  </div>
);

// Main diagram
const OrbotUiDiagram: React.FC = () => (
  <div style={{ fontFamily: 'Inter, Arial, sans-serif', background: '#f8f8f8', minHeight: '100vh', padding: 0 }}>
    {/* Title, not obstructing graph */}
    <div style={{ textAlign: 'center', fontSize: 28, fontWeight: 700, margin: '32px 0 16px 0', color: '#1976d2', letterSpacing: 1, zIndex: 2, position: 'relative' }}>
      Orbot UI as of 12/7/2025
    </div>
    {/* Activities row */}
    <div style={{ display: 'flex', justifyContent: 'center', gap: 32, flexWrap: 'wrap', marginBottom: 16 }}>
      {uiGraph.activities.map((act, i) => (
        <GraphNode
          key={i}
          label={<div><b>{act.name}</b><br />{fileLink(act.file)}</div>}
          style={{ background: act.nav ? '#fffde7' : '#fff', minWidth: 160 }}
        />
      ))}
    </div>
    {/* Navigation node and its tabs */}
    <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 0 }}>
      <GraphNode
        label={<div><b>{uiGraph.nav.label}</b><br />{fileLink(uiGraph.nav.file)}</div>}
        style={{ background: '#e8f5e9', minWidth: 220 }}
      >
        <NavGraph />
      </GraphNode>
    </div>
    <div style={{ textAlign: 'center', color: '#888', fontSize: 13, marginTop: 32 }}>
      Click any file path to open it on disk (if supported by your environment).
    </div>
  </div>
);

export default OrbotUiDiagram;
